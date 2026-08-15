package com.tcms.integration;

import java.util.UUID;

/**
 * One channel, resolved and ready to be delivered to.
 *
 * <p>{@link #name} is carried alongside the id because every failure message an operator reads
 * names the channel, and by the time a delivery fails the transaction that could look the name up
 * has closed. Fetching it again on the error path is a query issued only when something is already
 * wrong, which is the query most likely to fail for a second reason.
 *
 * <p>Holds a {@link ChannelConfig} and therefore a decrypted credential — see that record's header
 * for why it must not be interpolated into anything.
 */
record ChannelTarget(UUID channelId, String name, ChannelConfig config) {
}
