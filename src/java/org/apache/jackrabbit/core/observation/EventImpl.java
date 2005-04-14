/*
 * Copyright 2004-2005 The Apache Software Foundation or its licensors,
 *                     as applicable.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.jackrabbit.core.observation;

import org.apache.jackrabbit.core.MalformedPathException;
import org.apache.jackrabbit.core.NoPrefixDeclaredException;
import org.apache.jackrabbit.core.Path;
import org.apache.jackrabbit.core.SessionImpl;
import org.apache.log4j.Logger;

import javax.jcr.RepositoryException;
import javax.jcr.observation.Event;

/**
 * Implementation of the {@link javax.jcr.observation.Event} interface.
 */
public final class EventImpl implements Event {

    /**
     * Logger instance for this class
     */
    private static final Logger log = Logger.getLogger(EventImpl.class);

    /**
     * The session of the {@link javax.jcr.observation.EventListener} this
     * event will be delivered to.
     */
    private final SessionImpl session;

    /**
     * The <code>ItemManager</code> of the session.
     */
    //private final ItemManager itemMgr;

    /**
     * The shared {@link EventState} object.
     */
    private final EventState eventState;

    /**
     * Cached String value of this <code>Event</code> instance.
     */
    private String stringValue;

    /**
     * Creates a new {@link javax.jcr.observation.Event} instance based on an
     * {@link EventState eventState}.
     *
     * @param session    the session of the registerd <code>EventListener</code>
     *                   where this <code>Event</code> will be delivered to.
     * @param eventState the underlying <code>EventState</code>.
     */
    EventImpl(SessionImpl session, EventState eventState) {
        this.session = session;
        this.eventState = eventState;
    }

    /**
     * {@inheritDoc}
     */
    public int getType() {
        return eventState.getType();
    }

    /**
     * {@inheritDoc}
     */
    public String getPath() throws RepositoryException {
        try {
            Path p;
            if (eventState.getChildRelPath().getIndex() > 0) {
                p = Path.create(eventState.getParentPath(), eventState.getChildRelPath().getName(), eventState.getChildRelPath().getIndex(), false);
            } else {
                p = Path.create(eventState.getParentPath(), eventState.getChildRelPath().getName(), false);
            }
            return p.toJCRPath(session.getNamespaceResolver());
        } catch (MalformedPathException e) {
            String msg = "internal error: malformed path for event";
            log.debug(msg);
            throw new RepositoryException(msg, e);
        } catch (NoPrefixDeclaredException e) {
            String msg = "internal error: encountered unregistered namespace in path";
            log.debug(msg);
            throw new RepositoryException(msg, e);
        }
    }

    /**
     * {@inheritDoc}
     */
    public String getUserID() {
        return eventState.getUserId();
    }

    /**
     * Returns the uuid of the parent node.
     *
     * @return the uuid of the parent node.
     */
    public String getParentUUID() {
        return eventState.getParentUUID();
    }

    /**
     * Returns the UUID of a child node operation.
     * If this <code>Event</code> was generated for a property
     * operation this method returns <code>null</code>.
     *
     * @return the UUID of a child node operation.
     */
    public String getChildUUID() {
        return eventState.getChildUUID();
    }

    /**
     * Returns a String representation of this <code>Event</code>.
     *
     * @return a String representation of this <code>Event</code>.
     */
    public String toString() {
        if (stringValue == null) {
            StringBuffer sb = new StringBuffer();
            sb.append("Event: Path: ");
            try {
                sb.append(getPath());
            } catch (RepositoryException e) {
                log.error("Exception retrieving path: " + e);
                sb.append("[Error retrieving path]");
            }
            sb.append(", ").append(EventState.valueOf(getType())).append(": ");
            sb.append(", UserId: ").append(getUserID());
            stringValue = sb.toString();
        }
        return stringValue;
    }

    /**
     * @see Object#hashCode()
     */
    public int hashCode() {
        return eventState.hashCode() ^ session.hashCode();
    }

    /**
     * Returns <code>true</code> if this <code>Event</code> is equal to another
     * object.
     * <p/>
     * Two <code>Event</code> instances are equal if their respective
     * <code>EventState</code> instances are equal and both <code>Event</code>
     * instances are intended for the same <code>Session</code> that registerd
     * the <code>EventListener</code>.
     *
     * @param obj the reference object with which to compare.
     * @return <code>true</code> if this <code>Event</code> is equal to another
     *         object.
     */
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj instanceof EventImpl) {
            EventImpl other = (EventImpl) obj;
            return this.eventState.equals(other.eventState)
                    && this.session.equals(other.session);
        }
        return false;
    }
}
