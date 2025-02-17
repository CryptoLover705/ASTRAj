/*
 * Copyright 2013 Google Inc.
 * Copyright 2014 Andreas Schildbach
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.astraj.examples;

import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;
import org.astraj.core.*;
import org.astraj.kits.WalletAppKit;
import org.astraj.params.RegTestParams;
import org.astraj.protocols.channels.PaymentChannelClient;
import org.astraj.protocols.channels.PaymentChannelClientConnection;
import org.astraj.protocols.channels.StoredPaymentChannelClientStates;
import org.astraj.protocols.channels.ValueOutOfRangeException;
import org.astraj.utils.BriefLogFormatter;
import org.astraj.utils.Threading;
import org.astraj.wallet.Wallet;
import org.astraj.wallet.WalletExtension;

import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.Uninterruptibles;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;

import static org.astraj.core.Coin.CENT;

/**
 * Simple client that connects to the given host, opens a channel, and pays one cent.
 */
public class ExamplePaymentChannelClient {
    private static final org.slf4j.Logger log = LoggerFactory.getLogger(ExamplePaymentChannelClient.class);
    private WalletAppKit appKit;
    private final Coin channelSize;
    private final ECKey myKey;
    private final NetworkParameters params;

    public static void main(String[] args) throws Exception {
        BriefLogFormatter.init();
        OptionParser parser = new OptionParser();
        OptionSpec<NetworkEnum> net = parser.accepts("net", "The network to run the examples on").withRequiredArg().ofType(NetworkEnum.class).defaultsTo(NetworkEnum.TEST);
        OptionSpec<Integer> version = parser.accepts("version", "The payment channel protocol to use").withRequiredArg().ofType(Integer.class);
        parser.accepts("help", "Displays program options");
        OptionSet opts = parser.parse(args);
        if (opts.has("help") || !opts.has(net) || opts.nonOptionArguments().size() != 1) {
            System.err.println("usage: ExamplePaymentChannelClient --net=MAIN/TEST/REGTEST --version=1/2 host");
            parser.printHelpOn(System.err);
            return;
        }
        PaymentChannelClient.VersionSelector versionSelector = PaymentChannelClient.VersionSelector.VERSION_1;
        if (opts.has("version")) {
            switch (version.value(opts)) {
                case 1:
                    versionSelector = PaymentChannelClient.VersionSelector.VERSION_1;
                    break;
                case 2:
                    versionSelector = PaymentChannelClient.VersionSelector.VERSION_2;
                    break;
                default:
                    System.err.println("Invalid version - valid versions are 1, 2");
                    return;
            }
        }
        NetworkParameters params = net.value(opts).get();
        new ExamplePaymentChannelClient().run(opts.nonOptionArguments().get(0), versionSelector, params);
    }

    public ExamplePaymentChannelClient() {
        channelSize = CENT;
        myKey = new ECKey();
        params = RegTestParams.get();
    }

    public void run(final String host, PaymentChannelClient.VersionSelector versionSelector, final NetworkParameters params) throws Exception {
        // Bring up all the objects we need, create/load a wallet, sync the chain, etc. We override WalletAppKit so we
        // can customize it by adding the extension objects - we have to do this before the wallet file is loaded so
        // the plugin that knows how to parse all the additional data is present during the load.
        appKit = new WalletAppKit(params, new File("."), "payment_channel_example_client") {
            @Override
            protected List<WalletExtension> provideWalletExtensions() {
                // The StoredPaymentChannelClientStates object is responsible for, amongst other things, broadcasting
                // the refund transaction if its lock time has expired. It also persists channels so we can resume them
                // after a restart.
                // We should not send a PeerGroup in the StoredPaymentChannelClientStates constructor
                // since WalletAppKit will find it for us.
                return ImmutableList.<WalletExtension>of(new StoredPaymentChannelClientStates(null));
            }
        };
        // Broadcasting can take a bit of time so we up the timeout for "real" networks
        final int timeoutSeconds = params.getId().equals(NetworkParameters.ID_REGTEST) ? 15 : 150;
        if (params == RegTestParams.get()) {
            appKit.connectToLocalHost();
        }
        appKit.startAsync();
        appKit.awaitRunning();
        // We now have active network connections and a fully synced wallet.
        // Add a new key which will be used for the multisig contract.
        appKit.wallet().importKey(myKey);
        appKit.wallet().allowSpendingUnconfirmedTransactions();

        System.out.println(appKit.wallet());

        // Create the object which manages the payment channels protocol, client side. Tell it where the server to
        // connect to is, along with some reasonable network timeouts, the wallet and our temporary key. We also have
        // to pick an amount of value to lock up for the duration of the channel.
        //
        // Note that this may or may not actually construct a new channel. If an existing unclosed channel is found in
        // the wallet, then it'll re-use that one instead.
        final InetSocketAddress server = new InetSocketAddress(host, 4242);

        waitForSufficientBalance(channelSize);
        final String channelID = host;
        // Do this twice as each one sends 1/10th of a bitcent 5 times, so to send a bitcent, we do it twice. This
        // demonstrates resuming a channel that wasn't closed yet. It should close automatically once we run out
        // of money on the channel.
        log.info("Round one ...");
        openAndSend(timeoutSeconds, server, channelID, 5, versionSelector);
        log.info("Round two ...");
        log.info(appKit.wallet().toString());
        openAndSend(timeoutSeconds, server, channelID, 4, versionSelector);   // 4 times because the opening of the channel made a payment.
        log.info("Stopping ...");
        appKit.stopAsync();
        appKit.awaitTerminated();
    }

    private void openAndSend(int timeoutSecs, InetSocketAddress server, String channelID, final int times, PaymentChannelClient.VersionSelector versionSelector) throws IOException, ValueOutOfRangeException, InterruptedException {
        // Use protocol version 1 for simplicity
        PaymentChannelClientConnection client = new PaymentChannelClientConnection(
                server, timeoutSecs, appKit.wallet(), myKey, channelSize, channelID, versionSelector);
        // Opening the channel requires talking to the server, so it's asynchronous.
        final CountDownLatch latch = new CountDownLatch(1);
        Futures.addCallback(client.getChannelOpenFuture(), new FutureCallback<PaymentChannelClientConnection>() {
            @Override
            public void onSuccess(PaymentChannelClientConnection client) {
                // By the time we get here, if the channel is new then we already made a micropayment! The reason is,
                // we are not allowed to have payment channels that pay nothing at all.
                log.info("Success! Trying to make {} micropayments. Already paid {} satoshis on this channel",
                        times, client.state().getValueSpent());
                final Coin MICROPAYMENT_SIZE = CENT.divide(10);
                for (int i = 0; i < times; i++) {
                    try {
                        // Wait because the act of making a micropayment is async, and we're not allowed to overlap.
                        // This callback is running on the user thread (see the last lines in openAndSend) so it's safe
                        // for us to block here: if we didn't select the right thread, we'd end up blocking the payment
                        // channels thread and would deadlock.
                        Uninterruptibles.getUninterruptibly(client.incrementPayment(MICROPAYMENT_SIZE));
                    } catch (ValueOutOfRangeException e) {
                        log.error("Failed to increment payment by a CENT, remaining value is {}", client.state().getValueRefunded());
                        throw new RuntimeException(e);
                    } catch (ExecutionException e) {
                        log.error("Failed to increment payment", e);
                        throw new RuntimeException(e);
                    }
                    log.info("Successfully sent payment of one CENT, total remaining on channel is now {}", client.state().getValueRefunded());
                }
                if (client.state().getValueRefunded().compareTo(MICROPAYMENT_SIZE) < 0) {
                    // Now tell the server we're done so they should broadcast the final transaction and refund us what's
                    // left. If we never do this then eventually the server will time out and do it anyway and if the
                    // server goes away for longer, then eventually WE will time out and the refund tx will get broadcast
                    // by ourselves.
                    log.info("Settling channel for good");
                    client.settle();
                } else {
                    // Just unplug from the server but leave the channel open so it can resume later.
                    client.disconnectWithoutSettlement();
                }
                latch.countDown();
            }

            @Override
            public void onFailure(Throwable throwable) {
                log.error("Failed to open connection", throwable);
                latch.countDown();
            }
        }, Threading.USER_THREAD);
        latch.await();
    }

    private void waitForSufficientBalance(Coin amount) {
        // Not enough money in the wallet.
        Coin amountPlusFee = amount.add(Transaction.REFERENCE_DEFAULT_MIN_TX_FEE);
        // ESTIMATED because we don't really need to wait for confirmation.
        ListenableFuture<Coin> balanceFuture = appKit.wallet().getBalanceFuture(amountPlusFee, Wallet.BalanceType.ESTIMATED);
        if (!balanceFuture.isDone()) {
            System.out.println("Please send " + amountPlusFee.toFriendlyString() +
                    " to " + myKey.toAddress(params));
            Futures.getUnchecked(balanceFuture);
        }
    }
}
