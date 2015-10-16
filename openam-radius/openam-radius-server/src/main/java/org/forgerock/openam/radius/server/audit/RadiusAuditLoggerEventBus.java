/*
 * The contents of this file are subject to the terms of the Common Development and
 * Distribution License (the License). You may not use this file except in compliance with the
 * License.
 *
 * You can obtain a copy of the License at legal/CDDLv1.0.txt. See the License for the
 * specific language governing permission and limitations under the License.
 *
 * When distributing Covered Software, include this CDDL Header Notice in each file and include
 * the License file at legal/CDDLv1.0.txt. If applicable, add the following below the CDDL
 * Header, with the fields enclosed by brackets [] replaced by your own identifying
 * information: "Portions copyright [year] [name of copyright owner]".
 *
 * Copyright 2015 ForgeRock AS.
 */
package org.forgerock.openam.radius.server.audit;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;
import javax.inject.Named;

import org.forgerock.audit.AuditException;
import org.forgerock.audit.events.AccessAuditEventBuilder.ResponseStatus;
import org.forgerock.guava.common.base.Strings;
import org.forgerock.guava.common.eventbus.EventBus;
import org.forgerock.guava.common.eventbus.Subscribe;
import org.forgerock.openam.audit.AMAccessAuditEventBuilder;
import org.forgerock.openam.audit.AuditConstants;
import org.forgerock.openam.audit.AuditConstants.Component;
import org.forgerock.openam.audit.AuditConstants.EventName;
import org.forgerock.openam.audit.AuditEventFactory;
import org.forgerock.openam.audit.AuditEventPublisher;
import org.forgerock.openam.radius.common.PacketType;
import org.forgerock.openam.radius.server.RadiusRequestContext;
import org.forgerock.openam.radius.server.RadiusResponse;
import org.forgerock.openam.radius.server.config.RadiusServerConstants;
import org.forgerock.openam.radius.server.events.AcceptedRadiusEvent;
import org.forgerock.openam.radius.server.events.AuthRequestAcceptedEvent;
import org.forgerock.openam.radius.server.events.AuthRequestChallengedEvent;
import org.forgerock.openam.radius.server.events.AuthRequestReceivedEvent;
import org.forgerock.openam.radius.server.events.AuthRequestRejectedEvent;

import com.sun.identity.shared.debug.Debug;

/**
 * Makes audit logs on behalf of the Radius Server.
 */
public class RadiusAuditLoggerEventBus implements RadiusAuditLogger {

    private static final Debug LOG = Debug.getInstance(RadiusServerConstants.RADIUS_SERVER_LOGGER);

    /**
     * Factory from which auditEvents can be created.
     */
    private final AuditEventFactory auditEventFactory;

    /**
     * Class to which audit events should be published.
     */
    private AuditEventPublisher auditEventPublisher;

    @Inject
    public RadiusAuditLoggerEventBus(@Named("RadiusEventBus") EventBus eventBus, AuditEventFactory eventFactory,
            AuditEventPublisher eventPublisher) {
        LOG.message("Entering RadiusAuditLogger.RadiusAuditLogger");
        LOG.message("Registering RadiusAuditLogger with the eventBus, hashCode; " + eventBus.hashCode());
        eventBus.register(this);
        this.auditEventFactory = eventFactory;
        this.auditEventPublisher = eventPublisher;
        LOG.message("Leaving RadiusAuditLogger.RadiusAuditLogger");
    }

    /* (non-Javadoc)
     * @see org.forgerock.openam.radius.server.audit.RadiusAuditLogger#recordAccessRequest(org.forgerock.openam.radius.server.events.AccessRequestEvent)
     */
    @Override
    @Subscribe
    public void recordAuthRequestReceivedEvent(AuthRequestReceivedEvent authRequestReceivedEvent) {
        LOG.message("Entering RadiusAuditLoggerEventBus.recordAuthRequestReceivedEvent()");
        makeLogEntry(EventName.AM_ACCESS_ATTEMPT, authRequestReceivedEvent);
        LOG.message("Leaving RadiusAuditLoggerEventBus.recordAuthRequestReceivedEvent()");
    }

    @Override
    @Subscribe
    public void recordAuthRequestAcceptedEvent(AuthRequestAcceptedEvent authRequestAcceptedEvent) {
        LOG.message("Entering RadiusAuditLoggerEventBus.recordAuthRequestAcceptedEvent()");
        makeLogEntry(EventName.AM_ACCESS_OUTCOME, authRequestAcceptedEvent);
        LOG.message("Leaving RadiusAuditLoggerEventBus.recordAuthRequestAcceptedEvent()");
    }
    
    
    @Override
    @Subscribe
    public void recordAuthRequestRejectedEvent(AuthRequestRejectedEvent authRequestRejectedEvent) {
        LOG.message("Entering RadiusAuditLoggerEventBus.recordAuthRequestRejectedEvent()");
        makeLogEntry(EventName.AM_ACCESS_OUTCOME, authRequestRejectedEvent);
        LOG.message("Leaving RadiusAuditLoggerEventBus.recordAuthRequestRejectedEvent()");
    }

    @Override
    @Subscribe
    public void recordAuthRequestChallengedEvent(AuthRequestChallengedEvent authRequestChallengedEvent) {
        LOG.message("Entering RadiusAuditLoggerEventBus.recordAuthRequestRejectedEvent()");
        makeLogEntry(EventName.AM_ACCESS_OUTCOME, authRequestChallengedEvent);
        LOG.message("Leaving RadiusAuditLoggerEventBus.recordAuthRequestRejectedEvent()");
    }

    /**
     * Makes an 'access' audit log entry.
     * 
     * @param eventName
     *            - the name of the event.
     * @param accessRequestEvent
     *            - the access request event.
     */
    public void makeLogEntry(EventName eventName, AcceptedRadiusEvent accessRequestEvent) {
        LOG.message("Entering RadiusAuditLoggerEventBus.makeLogEntry()");
        Set<String> trackingIds = new HashSet<String>();
        trackingIds.add(accessRequestEvent.getUsername());
        trackingIds.add(accessRequestEvent.getRequest().getContextHolderKey());

        AMAccessAuditEventBuilder builder = auditEventFactory.accessEvent(accessRequestEvent.getRealm())
                .timestamp(accessRequestEvent.getTimeOfEvent())
                .transactionId("RADIUS_" + accessRequestEvent.getRequestId())
                .eventName(eventName)
                .component(Component.RADIUS)
                .authentication(accessRequestEvent.getUsername())
                .trackingIds(trackingIds);

        try {
            setClientDetails(builder, accessRequestEvent.getRequestContext());
            RadiusResponse response = accessRequestEvent.getResponse();

            if (response.getResponsePacket() != null) {
                setResponseDetails(builder, response);
            }
        } catch (RadiusAuditLoggingException e) {
            LOG.warning("Failed to set client details on access audit event. Reason; " + e.getMessage());
        }
        
        try {
            this.auditEventPublisher.publish(AuditConstants.ACCESS_TOPIC, builder.toEvent());
        } catch (AuditException e) {
            LOG.warning("Failed to publish audit event.", e);
        }
        LOG.message("Leaving RadiusAuditLoggerEventBus.makeLogEntry()");
    }


    /**
     * Sets the client details via the access event builder.
     * 
     * @param builder
     *            - the AccessAuditEventBuilder to which the client details should be added.
     * @param radiusRequestContext
     * @throws RadiusAuditLoggingException
     */
    private void setClientDetails(AMAccessAuditEventBuilder builder, RadiusRequestContext radiusRequestContext)
            throws RadiusAuditLoggingException {
        String clientIPAddress = null;
        InetSocketAddress source = radiusRequestContext.getSource();
        if (source == null) {
            throw new RadiusAuditLoggingException("Could not obtain the source address form the request context.");
        } else {
            int port = source.getPort();
            InetAddress address = source.getAddress();
            if (address == null) {
                throw new RadiusAuditLoggingException("Could not obtain the address from the InetSocketAddress.");

            } else {
                clientIPAddress = address.toString();
                if (Strings.isNullOrEmpty(clientIPAddress)) {
                    throw new RadiusAuditLoggingException("String representation of client's ip address is blank.");
                } else {
                    builder.client(clientIPAddress, port);
                }
            }
        }
    }

    /**
     * Sets the response details of the builder, using the details provided in the <code>RadiusResponse</code>
     * 
     * @param builder
     * @param response
     */
    private void setResponseDetails(AMAccessAuditEventBuilder builder, RadiusResponse response) {
        LOG.message("Entering RadiusAuditLoggerEventBus.setResponseDetails()");
        ResponseStatus responseStatus = null;
        PacketType packetType = response.getResponsePacket().getType();
        
        if ((packetType == PacketType.ACCESS_ACCEPT) ||
                (packetType == PacketType.ACCESS_CHALLENGE)) {
            responseStatus = ResponseStatus.SUCCESS;
        } else if (packetType == PacketType.ACCESS_REJECT) {
            responseStatus = ResponseStatus.FAILURE;
        } else {
            LOG.warning("Unexpected packet type in RadiusAuditLoggerEventBus.setResponseDetails()");
        }

        builder.response(responseStatus,
                packetType.toString(),
                response.getTimeToServiceRequestInMilliSeconds(),
                TimeUnit.MILLISECONDS);
        
        LOG.message("Leaving RadiusAuditLoggerEventBus.setResponseDetails()");
    }


}
