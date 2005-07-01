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

import org.apache.jackrabbit.core.ItemId;
import org.apache.jackrabbit.core.NodeId;
import org.apache.jackrabbit.core.PropertyId;
import org.apache.jackrabbit.core.SessionImpl;
import org.apache.jackrabbit.core.security.AccessManager;
import org.apache.log4j.Logger;

import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.observation.Event;
import javax.jcr.observation.EventIterator;
import javax.jcr.observation.EventListener;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * The <code>EventConsumer</code> class combines the {@link
 * javax.jcr.observation.EventListener} with the implementation of specified
 * filter for the listener: {@link EventFilter}.
 * <p/>
 * Collections of {@link EventState} objects will be dispatched to {@link
 * #consumeEvents}.
 */
class EventConsumer {

    /**
     * The default Logger instance for this class.
     */
    private static final Logger log = Logger.getLogger(EventConsumer.class);

    /**
     * The <code>Session</code> associated with this <code>EventConsumer</code>.
     */
    private final SessionImpl session;

    /**
     * The listener part of this <code>EventConsumer</code>.
     */
    private final EventListener listener;

    /**
     * The <code>EventFilter</code> for this <code>EventConsumer</code>.
     */
    private final EventFilter filter;

    /**
     * A map of <code>Set</code> objects that hold references to denied
     * <code>EventState</code>s. The map uses the <code>EventStateCollection</code>
     * as the key to reference a deny Set.
     */
    private final Map accessDenied = Collections.synchronizedMap(new HashMap());

    /**
     * cached hash code value
     */
    private int hashCode;

    /**
     * An <code>EventConsumer</code> consists of a <code>Session</code>, the
     * attached <code>EventListener</code> and an <code>EventFilter</code>.
     *
     * @param session  the <code>Session</code> that created this
     *                 <code>EventConsumer</code>.
     * @param listener the actual <code>EventListener</code> to call back.
     * @param filter   only pass an <code>Event</code> to the listener if the
     *                 <code>EventFilter</code> allows the <code>Event</code>.
     * @throws NullPointerException if <code>session</code>, <code>listener</code>
     *                              or <code>filter</code> is<code>null</code>.
     */
    EventConsumer(SessionImpl session, EventListener listener, EventFilter filter)
            throws NullPointerException {
        if (session == null) {
            throw new NullPointerException("session");
        }
        if (listener == null) {
            throw new NullPointerException("listener");
        }
        if (filter == null) {
            throw new NullPointerException("filter");
        }

        this.session = session;
        this.listener = listener;
        this.filter = filter;
    }

    /**
     * Returns the <code>Session</code> that is associated
     * with this <code>EventConsumer</code>.
     *
     * @return the <code>Session</code> of this <code>EventConsumer</code>.
     */
    Session getSession() {
        return session;
    }

    /**
     * Returns the <code>EventListener</code> that is associated with this
     * <code>EventConsumer</code>.
     *
     * @return the <code>EventListener</code> of this <code>EventConsumer</code>.
     */
    EventListener getEventListener() {
        return listener;
    }

    /**
     * Checks for what {@link EventState}s this <code>EventConsumer</code> has
     * enough access rights to see the event.
     *
     * @param events the collection of {@link EventState}s.
     */
    void prepareEvents(EventStateCollection events) {
        Iterator it = events.iterator();
        Set denied = null;
        while (it.hasNext()) {
            EventState state = (EventState) it.next();
            if (state.getType() == Event.NODE_REMOVED
                    || state.getType() == Event.PROPERTY_REMOVED) {

                if (session.equals(state.getSession())) {
                    // if we created the event, we can be sure that
                    // we have enough access rights to see the event
                    continue;
                }

                // check read permission
                ItemId targetId;
                if (state.getChildUUID() == null) {
                    // target is a property
                    targetId = new PropertyId(state.getParentUUID(), state.getChildRelPath().getName());
                } else {
                    // target is a node
                    targetId = new NodeId(state.getChildUUID());
                }
                boolean granted = false;
                try {
                    granted = session.getAccessManager().isGranted(targetId, AccessManager.READ);
                } catch (RepositoryException e) {
                    log.warn("Unable to check access rights for item: " + targetId);
                }
                if (!granted) {
                    if (denied == null) {
                        denied = new HashSet();
                    }
                    denied.add(state);
                }
            }
        }
        if (denied != null) {
            accessDenied.put(events, denied);
        }
    }

    /**
     * Dispatches the events to the <code>EventListener</code>.
     *
     * @param events a collection of {@link EventState}s
     *               to dispatch.
     */
    void consumeEvents(EventStateCollection events) throws RepositoryException {
        Set denied = (Set) accessDenied.remove(events);
        // check permissions
        for (Iterator it = events.iterator(); it.hasNext();) {
            EventState state = (EventState) it.next();
            if (state.getType() == Event.NODE_ADDED
                    || state.getType() == Event.PROPERTY_ADDED
                    || state.getType() == Event.PROPERTY_CHANGED) {
                ItemId targetId;
                if (state.getChildUUID() == null) {
                    // target is a property
                    targetId = new PropertyId(state.getParentUUID(), state.getChildRelPath().getName());
                } else {
                    // target is a node
                    targetId = new NodeId(state.getChildUUID());
                }
                if (!session.getAccessManager().isGranted(targetId, AccessManager.READ)) {
                    if (denied == null) {
                        denied = new HashSet();
                    }
                    denied.add(state);
                }
            }
        }
        // check if filtered iterator has at least one event
        EventIterator it = new FilteredEventIterator(events, filter, denied);
        if (it.hasNext()) {
            listener.onEvent(it);
        } else {
            // otherwise skip this listener
        }
    }

    /**
     * Returns <code>true</code> if this <code>EventConsumer</code> is equal to
     * some other object, <code>false</code> otherwise.
     * <p/>
     * Two <code>EventConsumer</code>s are considered equal if they refer to the
     * same <code>Session</code> and the <code>EventListener</code>s they
     * reference are equal. Note that the <code>EventFilter</code> is ignored in
     * this check.
     *
     * @param obj the reference object with which to compare.
     * @return <code>true</code> if this <code>EventConsumer</code> is equal the
     *         other <code>EventConsumer</code>.
     */
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj instanceof EventConsumer) {
            EventConsumer other = (EventConsumer) obj;
            return session.equals(other.session)
                    && listener.equals(other.listener);
        }
        return false;
    }

    /**
     * Returns the hash code for this <code>EventConsumer</code>.
     *
     * @return the hash code for this <code>EventConsumer</code>.
     */
    public int hashCode() {
        if (hashCode == 0) {
            hashCode = session.hashCode() ^ listener.hashCode();
        }
        return hashCode;
    }
}
