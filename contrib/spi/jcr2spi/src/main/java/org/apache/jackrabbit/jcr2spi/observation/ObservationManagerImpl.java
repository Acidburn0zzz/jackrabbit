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
package org.apache.jackrabbit.jcr2spi.observation;

import org.apache.jackrabbit.jcr2spi.nodetype.NodeTypeRegistry;
import org.apache.jackrabbit.util.IteratorHelper;
import org.apache.jackrabbit.name.MalformedPathException;
import org.apache.jackrabbit.name.NameFormat;
import org.apache.jackrabbit.name.NamespaceResolver;
import org.apache.jackrabbit.name.QName;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;

import javax.jcr.observation.ObservationManager;
import javax.jcr.observation.EventListener;
import javax.jcr.observation.EventListenerIterator;
import javax.jcr.RepositoryException;
import org.apache.jackrabbit.name.Path;
import org.apache.jackrabbit.name.NameException;
import org.apache.jackrabbit.spi.EventIterator;

import java.util.HashMap;
import java.util.Map;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;

/**
 * <code>ObservationManagerImpl</code>...
 */
public class ObservationManagerImpl implements ObservationManager, InternalEventListener {

    /**
     * The logger instance for this class.
     */
    private static final Logger log = LoggerFactory.getLogger(ObservationManagerImpl.class);

    /**
     * The session this observation manager belongs to.
     */
    private final NamespaceResolver nsResolver;

    /**
     * The <code>NodeTypeRegistry</code> of the session.
     */
    private final NodeTypeRegistry ntRegistry;

    /**
     * Live mapping of <code>EventListener</code> to <code>EventFilter</code>.
     */
    private final Map subscriptions = new HashMap();

    /**
     * A read only mapping of <code>EventListener</code> to <code>EventFilter</code>.
     */
    private Map readOnlySubscriptions;

    /**
     * Creates a new observation manager for <code>session</code>.
     * @param nsResolver NamespaceResolver to be used by this observation manager
     * is based on.
     * @param ntRegistry The <code>NodeTypeRegistry</code> of the session.
     */
    public ObservationManagerImpl(NamespaceResolver nsResolver, NodeTypeRegistry ntRegistry) {
        this.nsResolver = nsResolver;
        this.ntRegistry = ntRegistry;
    }

    /**
     * @inheritDoc
     */
    public void addEventListener(EventListener listener,
                                 int eventTypes,
                                 String absPath,
                                 boolean isDeep,
                                 String[] uuid,
                                 String[] nodeTypeName,
                                 boolean noLocal) throws RepositoryException {
        Path path;
        try {
            path = nsResolver.getQPath(absPath).getCanonicalPath();
        } catch (MalformedPathException e) {
            throw new RepositoryException("Malformed path: " + absPath);
        }

        // create NodeType instances from names
        QName[] nodeTypeNames;
        if (nodeTypeName == null) {
            nodeTypeNames = null;
        } else {
            try {
                nodeTypeNames = new QName[nodeTypeName.length];
                for (int i = 0; i < nodeTypeName.length; i++) {
                    QName ntName = NameFormat.parse(nodeTypeName[i], nsResolver);
                    if (!ntRegistry.isRegistered(ntName)) {
                        throw new RepositoryException("unknown node type: " + nodeTypeName[i]);
                    }
                    nodeTypeNames[i] = ntName;
                }
            } catch (NameException e) {
                throw new RepositoryException(e.getMessage());
            }
        }

        synchronized (subscriptions) {
            EventFilter filter = new EventFilter(nsResolver, ntRegistry,
                    eventTypes, path, isDeep, uuid, nodeTypeNames, noLocal);
            subscriptions.put(listener, filter);
            readOnlySubscriptions = null;
        }
    }

    /**
     * @inheritDoc
     */
    public void removeEventListener(EventListener listener) throws RepositoryException {
        synchronized (subscriptions) {
            if (subscriptions.remove(listener) != null) {
                readOnlySubscriptions = null;
            }
        }
    }

    /**
     * @inheritDoc
     */
    public EventListenerIterator getRegisteredEventListeners() throws RepositoryException {
        Map activeListeners;
        synchronized (subscriptions) {
            ensureReadOnlyMap();
            activeListeners = readOnlySubscriptions;
        }
        return new ListenerIterator(activeListeners.keySet());
    }

    //-----------------------< InternalEventListener >--------------------------

    public void onEvent(EventIterator events, boolean isLocal) {
        List eventList = new ArrayList();
        while (events.hasNext()) {
            eventList.add(events.nextEvent());
        }
        eventList = Collections.unmodifiableList(eventList);

        // get active listeners
        Map activeListeners;
        synchronized (subscriptions) {
            ensureReadOnlyMap();
            activeListeners = readOnlySubscriptions;
        }
        for (Iterator it = activeListeners.keySet().iterator(); it.hasNext(); ) {
            EventListener listener = (EventListener) it.next();
            EventFilter filter = (EventFilter) activeListeners.get(listener);
            FilteredEventIterator eventIter = new FilteredEventIterator(eventList, filter, isLocal);
            if (eventIter.hasNext()) {
                try {
                    listener.onEvent(eventIter);
                } catch (Throwable t) {
                    log.warn("EventConsumer threw exception: " + t.toString());
                    log.debug("Stacktrace: ", t);
                    // move on to the next listener
                }
            }
        }
    }

    //-------------------------< internal >-------------------------------------

    /**
     * Ensures that {@link #readOnlySubscriptions} is set. Callers of this
     * method must own {@link #subscriptions} as a monitor to avoid concurrent
     * access to {@link #subscriptions}.
     */
    private void ensureReadOnlyMap() {
        if (readOnlySubscriptions == null) {
            readOnlySubscriptions = new HashMap(subscriptions);
        }
    }

    private static final class ListenerIterator extends IteratorHelper
            implements EventListenerIterator {

        public ListenerIterator(Collection c) {
            super(c);
        }

        public EventListener nextEventListener() {
            return (EventListener) next();
        }
    }
}
