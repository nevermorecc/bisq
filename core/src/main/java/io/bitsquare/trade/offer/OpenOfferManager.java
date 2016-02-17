/*
 * This file is part of Bitsquare.
 *
 * Bitsquare is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * Bitsquare is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Bitsquare. If not, see <http://www.gnu.org/licenses/>.
 */

package io.bitsquare.trade.offer;

import com.google.inject.Inject;
import io.bitsquare.app.Log;
import io.bitsquare.btc.TradeWalletService;
import io.bitsquare.btc.WalletService;
import io.bitsquare.common.UserThread;
import io.bitsquare.common.crypto.KeyRing;
import io.bitsquare.common.handlers.ErrorMessageHandler;
import io.bitsquare.common.handlers.ResultHandler;
import io.bitsquare.p2p.BootstrapListener;
import io.bitsquare.p2p.Message;
import io.bitsquare.p2p.NodeAddress;
import io.bitsquare.p2p.P2PService;
import io.bitsquare.p2p.messaging.SendDirectMessageListener;
import io.bitsquare.storage.Storage;
import io.bitsquare.trade.TradableList;
import io.bitsquare.trade.closed.ClosedTradableManager;
import io.bitsquare.trade.handlers.TransactionResultHandler;
import io.bitsquare.trade.protocol.availability.messages.OfferAvailabilityRequest;
import io.bitsquare.trade.protocol.availability.messages.OfferAvailabilityResponse;
import io.bitsquare.trade.protocol.placeoffer.PlaceOfferModel;
import io.bitsquare.trade.protocol.placeoffer.PlaceOfferProtocol;
import io.bitsquare.user.User;
import javafx.collections.ObservableList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import javax.inject.Named;
import java.io.File;
import java.util.Optional;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.TimeUnit;

import static com.google.inject.internal.util.$Preconditions.checkNotNull;
import static io.bitsquare.util.Validator.nonEmptyStringOf;

public class OpenOfferManager {
    private static final Logger log = LoggerFactory.getLogger(OpenOfferManager.class);

    private final KeyRing keyRing;
    private final User user;
    private final P2PService p2PService;
    private final WalletService walletService;
    private final TradeWalletService tradeWalletService;
    private final OfferBookService offerBookService;
    private final ClosedTradableManager closedTradableManager;

    private final TradableList<OpenOffer> openOffers;
    private final Storage<TradableList<OpenOffer>> openOffersStorage;
    private boolean shutDownRequested;
    private BootstrapListener bootstrapListener;
    private final Timer timer = new Timer();


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Inject
    public OpenOfferManager(KeyRing keyRing,
                            User user,
                            P2PService p2PService,
                            WalletService walletService,
                            TradeWalletService tradeWalletService,
                            OfferBookService offerBookService,
                            ClosedTradableManager closedTradableManager,
                            @Named("storage.dir") File storageDir) {
        this.keyRing = keyRing;
        this.user = user;
        this.p2PService = p2PService;
        this.walletService = walletService;
        this.tradeWalletService = tradeWalletService;
        this.offerBookService = offerBookService;
        this.closedTradableManager = closedTradableManager;

        openOffersStorage = new Storage<>(storageDir);
        this.openOffers = new TradableList<>(openOffersStorage, "OpenOffers");

        init();
    }

    private void init() {
        // In case the app did get killed the shutDown from the modules is not called, so we use a shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(OpenOfferManager.this::shutDown,
                "OpenOfferManager.ShutDownHook"));

        // Handler for incoming offer availability requests
        p2PService.addDecryptedDirectMessageListener((decryptedMessageWithPubKey, peersNodeAddress) -> {
            // We get an encrypted message but don't do the signature check as we don't know the peer yet.
            // A basic sig check is in done also at decryption time
            Message message = decryptedMessageWithPubKey.message;
            if (message instanceof OfferAvailabilityRequest)
                handleOfferAvailabilityRequest((OfferAvailabilityRequest) message, peersNodeAddress);
        });
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Lifecycle
    ///////////////////////////////////////////////////////////////////////////////////////////

    public void onAllServicesInitialized() {
        log.trace("onAllServicesInitialized");

        // We add own offers to offerbook when we go online again
        // setupAnStartRePublishThread will re-publish at method call

        // Before the TTL is reached we re-publish our offers
        // If offer removal at shutdown fails we don't want to have long term dangling dead offers, so we set 
        // TTL quite short and use re-publish as strategy. Offerers need to be online anyway.
        if (!p2PService.isBootstrapped()) {
            bootstrapListener = new BootstrapListener() {
                @Override
                public void onBootstrapComplete() {
                    startRePublishThread();
                }
            };
            p2PService.addP2PServiceListener(bootstrapListener);

        } else {
            startRePublishThread();
        }
    }

    private void startRePublishThread() {
        if (bootstrapListener != null)
            p2PService.removeP2PServiceListener(bootstrapListener);

        // republish sufficiently before offer would expire
        long period = (long) (Offer.TTL * 0.7);
        TimerTask timerTask = new TimerTask() {
            @Override
            public void run() {
                UserThread.execute(OpenOfferManager.this::rePublishOffers);
            }
        };
        timer.scheduleAtFixedRate(timerTask, 500, period);

        p2PService.getNumConnectedPeers().addListener((observable, oldValue, newValue) -> {
            if ((int) oldValue == 0 && (int) newValue > 0) {
                rePublishOffers();

                // We repeat a rePublishOffers call after 10 seconds if we have more than 3 peers
                UserThread.runAfter(() -> {
                    if (p2PService.getNumConnectedPeers().get() > 3)
                        rePublishOffers();
                }, 10);
            }
        });
    }

    private void rePublishOffers() {
        Log.traceCall("Number of offer for republish: " + openOffers.size());
        for (OpenOffer openOffer : openOffers) {
            offerBookService.republishOffer(openOffer.getOffer(),
                    () -> log.debug("Successful added offer to P2P network"),
                    errorMessage -> log.error("Add offer to P2P network failed. " + errorMessage));
            openOffer.setStorage(openOffersStorage);
        }
    }

    @SuppressWarnings("WeakerAccess")
    public void shutDown() {
        shutDown(null);
    }

    public void shutDown(@Nullable Runnable completeHandler) {
        if (timer != null)
            timer.cancel();

        if (!shutDownRequested) {
            log.info("remove all open offers at shutDown");
            shutDownRequested = true;
            // we remove own offers from offerbook when we go offline
            openOffers.forEach(openOffer -> offerBookService.removeOfferAtShutDown(openOffer.getOffer()));

            if (completeHandler != null)
                UserThread.runAfter(completeHandler::run, openOffers.size() * 200 + 300, TimeUnit.MILLISECONDS);
        }
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // API
    ///////////////////////////////////////////////////////////////////////////////////////////

    public void onPlaceOffer(Offer offer,
                             TransactionResultHandler resultHandler) {

        PlaceOfferModel model = new PlaceOfferModel(offer, walletService, tradeWalletService, offerBookService, user);

        PlaceOfferProtocol placeOfferProtocol = new PlaceOfferProtocol(
                model,
                transaction -> {
                    OpenOffer openOffer = new OpenOffer(offer, openOffersStorage);
                    openOffers.add(openOffer);
                    openOffersStorage.queueUpForSave();
                    resultHandler.handleResult(transaction);
                }
        );

        placeOfferProtocol.placeOffer();
    }


    public void onRemoveOpenOffer(Offer offer, ResultHandler resultHandler, ErrorMessageHandler errorMessageHandler) {
        Optional<OpenOffer> openOfferOptional = findOpenOffer(offer.getId());
        if (openOfferOptional.isPresent()) {
            onRemoveOpenOffer(openOfferOptional.get(), resultHandler, errorMessageHandler);
        } else {
            log.warn("Offer was not found in our list of open offers. We still try to remove it from the offerbook.");
            errorMessageHandler.handleErrorMessage("Offer was not found in our list of open offers. " +
                    "We still try to remove it from the offerbook.");
            offerBookService.removeOffer(offer,
                    () -> offer.setState(Offer.State.REMOVED),
                    null);
        }
    }

    public void onRemoveOpenOffer(OpenOffer openOffer, ResultHandler resultHandler, ErrorMessageHandler errorMessageHandler) {
        Offer offer = openOffer.getOffer();
        offerBookService.removeOffer(offer,
                () -> {
                    offer.setState(Offer.State.REMOVED);
                    openOffer.setState(OpenOffer.State.CANCELED);
                    openOffers.remove(openOffer);
                    closedTradableManager.add(openOffer);
                    resultHandler.handleResult();
                },
                errorMessageHandler);
    }

    public void reserveOpenOffer(OpenOffer openOffer) {
        openOffer.setState(OpenOffer.State.RESERVED);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Getters
    ///////////////////////////////////////////////////////////////////////////////////////////

    public boolean isMyOffer(Offer offer) {
        return offer.isMyOffer(keyRing);
    }

    public ObservableList<OpenOffer> getOpenOffers() {
        return openOffers.getObservableList();
    }

    public Optional<OpenOffer> findOpenOffer(String offerId) {
        return openOffers.stream().filter(openOffer -> openOffer.getId().equals(offerId)).findAny();
    }

    // Close openOffer after deposit published
    public void closeOpenOffer(Offer offer) {
        findOpenOffer(offer.getId()).ifPresent(openOffer -> {
            openOffers.remove(openOffer);
            openOffer.setState(OpenOffer.State.CLOSED);
            offerBookService.removeOffer(openOffer.getOffer(),
                    () -> log.trace("Successful removed offer"),
                    log::error);
        });

    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Offer Availability
    ///////////////////////////////////////////////////////////////////////////////////////////

    private void handleOfferAvailabilityRequest(OfferAvailabilityRequest message, NodeAddress sender) {
        log.trace("handleNewMessage: message = " + message.getClass().getSimpleName() + " from " + sender);
        try {
            nonEmptyStringOf(message.offerId);
            checkNotNull(message.getPubKeyRing());
        } catch (Throwable t) {
            log.warn("Invalid message " + message.toString());
            return;
        }

        Optional<OpenOffer> openOfferOptional = findOpenOffer(message.offerId);
        boolean isAvailable = openOfferOptional.isPresent() && openOfferOptional.get().getState() == OpenOffer.State.AVAILABLE;
        try {
            p2PService.sendEncryptedDirectMessage(sender,
                    message.getPubKeyRing(),
                    new OfferAvailabilityResponse(message.offerId, isAvailable),
                    new SendDirectMessageListener() {
                        @Override
                        public void onArrived() {
                            log.trace("OfferAvailabilityResponse successfully arrived at peer");
                        }

                        @Override
                        public void onFault() {
                            log.info("Sending OfferAvailabilityResponse failed.");
                        }
                    });
        } catch (Throwable t) {
            t.printStackTrace();
            log.info("Exception at handleRequestIsOfferAvailableMessage " + t.getMessage());
        }
    }

    public Optional<OpenOffer> getOpenOfferById(String offerId) {
        return openOffers.stream().filter(e -> e.getId().equals(offerId)).findFirst();
    }
}
