package com.tcms.integration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * What a notification email is sent as.
 *
 * <h2>A second from-address, and it is not a duplicate of the invitation one</h2>
 *
 * <p>{@code tcms.invitation.from-address} is the address a stranger receives an account activation
 * link from, and a deployment routinely wants that to be something a person answers. This is the
 * address a project's release channel receives fifty run notices from, which is the shape an
 * operator sends from a no-reply mailbox and filters on.
 *
 * <p>Defaulted rather than required, unlike the encryption key: a wrong from-address is a message
 * that arrives looking odd, and a missing one is a startup failure on every install that has no
 * notifications configured at all.
 *
 * @param fromAddress the envelope sender
 */
@ConfigurationProperties(prefix = "tcms.integration.mail")
public record NotificationMailProperties(String fromAddress) {
}
