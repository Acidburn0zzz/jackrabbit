/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
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

import org.apache.jackrabbit.core.SessionImpl;
import org.apache.jackrabbit.core.state.ChangeLog;
import org.apache.jackrabbit.name.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.List;

/**
 * This Class implements an observation dispatcher, that delegates events to
 * a set of underlying dispatchers.
 */
public class DelegatingObservationDispatcher extends EventDispatcher {

    /**
     * Logger instance.
     */
    private static Logger log = LoggerFactory.getLogger(DelegatingObservationDispatcher.class);

    /**
     * the set of dispatchers
     */
    private final HashSet dispatchers = new HashSet();

    /**
     * Adds a new observation factory to the set of dispatchers
     *
     * @param disp
     */
    public void addDispatcher(ObservationManagerFactory disp) {
        synchronized (dispatchers) {
            dispatchers.add(disp);
        }
    }

    /**
     * Removes a observation factory from the set of dispatchers
     *
     * @param disp
     */
    public void removeDispatcher(ObservationManagerFactory disp) {
        synchronized (dispatchers) {
            dispatchers.remove(disp);
        }
    }

    /**
     * Creates an <code>EventStateCollection</code> tied to the session
     * given as argument.
     *
     * @param session event source
     * @return new <code>EventStateCollection</code> instance
     */
    public EventStateCollection createEventStateCollection(SessionImpl session,
                                                           Path pathPrefix) {
        return new EventStateCollection(this, session, pathPrefix);
    }

    //------------------------------------------------------< EventDispatcher >

    /**
     * {@inheritDoc}
     */
    void prepareEvents(EventStateCollection events) {
        // events will get prepared on dispatch
    }

    /**
     * {@inheritDoc}
     */
    void prepareDeleted(EventStateCollection events, ChangeLog changes) {
        // events will get prepared on dispatch
    }

    /**
     * {@inheritDoc}
     */
    void dispatchEvents(EventStateCollection events) {
        dispatch(events.getEvents(), events.getSession(), events.getPathPrefix());
    }

    /**
     * Dispatchers a list of events to all registered dispatchers. A new
     * {@link EventStateCollection} is created for every dispatcher, fille with
     * the given event list and then dispatched.
     *
     * @param eventList
     * @param session
     */
    public void dispatch(List eventList, SessionImpl session, Path pathPrefix) {
        ObservationManagerFactory[] disp;
        synchronized (dispatchers) {
            disp = (ObservationManagerFactory[]) dispatchers.toArray(
                    new ObservationManagerFactory[dispatchers.size()]);
        }
        for (int i=0; i< disp.length; i++) {
            EventStateCollection events =
                    new EventStateCollection(disp[i], session, pathPrefix);
            try {
                events.addAll(eventList);
                events.prepare();
                events.dispatch();
            } catch (Exception e) {
                log.error("Error while dispatching events.", e);
            }
        }
    }
}
