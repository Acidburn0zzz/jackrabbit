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
package org.apache.jackrabbit.core;

import org.apache.jackrabbit.core.config.SearchConfig;
import org.apache.jackrabbit.core.fs.FileSystem;
import org.apache.jackrabbit.core.fs.FileSystemException;
import org.apache.jackrabbit.core.nodetype.NodeTypeRegistry;
import org.apache.jackrabbit.core.observation.EventImpl;
import org.apache.jackrabbit.core.observation.SynchronousEventListener;
import org.apache.jackrabbit.core.query.AbstractQueryImpl;
import org.apache.jackrabbit.core.query.QueryHandler;
import org.apache.jackrabbit.core.query.QueryHandlerContext;
import org.apache.jackrabbit.core.query.QueryImpl;
import org.apache.jackrabbit.core.state.ItemStateException;
import org.apache.jackrabbit.core.state.ItemStateManager;
import org.apache.jackrabbit.core.state.NodeState;
import org.apache.jackrabbit.core.state.NodeStateIterator;
import org.apache.jackrabbit.name.AbstractNamespaceResolver;
import org.apache.jackrabbit.name.NamespaceResolver;
import org.apache.jackrabbit.name.NoPrefixDeclaredException;
import org.apache.jackrabbit.name.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.NamespaceException;
import javax.jcr.NamespaceRegistry;
import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.observation.Event;
import javax.jcr.observation.EventIterator;
import javax.jcr.query.InvalidQueryException;
import javax.jcr.query.Query;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Properties;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.WeakHashMap;

/**
 * Acts as a global entry point to execute queries and index nodes.
 */
public class SearchManager implements SynchronousEventListener {

    /**
     * Logger instance for this class
     */
    private static final Logger log = LoggerFactory.getLogger(SearchManager.class);

    /**
     * Namespace URI for xpath functions
     */
    // @todo this is not final! What should we use?
    private static final String NS_FN_PREFIX = "fn";
    public static final String NS_FN_URI = "http://www.w3.org/2004/10/xpath-functions";

    /**
     * Namespace URI for XML schema
     */
    private static final String NS_XS_PREFIX = "xs";
    public static final String NS_XS_URI = "http://www.w3.org/2001/XMLSchema";

    /**
     * Name of the parameter that indicates the query implementation class.
     */
    private static final String PARAM_QUERY_IMPL = "queryClass";

    /**
     * Name of the parameter that specifies the idle time for a query handler.
     */
    private static final String PARAM_IDLE_TIME = "idleTime";

    /**
     * Name of the default query implementation class.
     */
    private static final String DEFAULT_QUERY_IMPL_CLASS = QueryImpl.class.getName();

    /**
     * Class instance that is shared for all <code>SearchManager</code> instances.
     * Each workspace will schedule a task to check if the query handler can
     * be shutdown after it had been idle for some time.
     */
    private static final Timer IDLE_TIMER = new Timer(true);

    /**
     * Idle time in seconds after which the query handler is shut down.
     */
    private static final int DEFAULT_IDLE_TIME = -1;

    /**
     * The time when the query handler was last accessed.
     */
    private long lastAccess = System.currentTimeMillis();

    /**
     * The search configuration.
     */
    private final SearchConfig config;

    /**
     * The node type registry.
     */
    private final NodeTypeRegistry ntReg;

    /**
     * The shared item state manager instance for the workspace.
     */
    private final ItemStateManager itemMgr;

    /**
     * Storage for search index
     */
    private final FileSystem fs;

    /**
     * The root node for this search manager.
     */
    private final NodeId rootNodeId;

    /**
     * QueryHandler where query execution is delegated to
     */
    private QueryHandler handler;

    /**
     * QueryHandler of the parent search manager or <code>null</code> if there
     * is none.
     */
    private final QueryHandler parentHandler;

    /**
     * Namespace resolver that is based on the namespace registry itself.
     */
    private final NamespaceResolver nsResolver;

    /**
     * ID of the node that should be excluded from indexing or <code>null</code>
     * if no node should be excluded.
     */
    private final NodeId excludedNodeId;

    /**
     * Path that will be excluded from indexing.
     */
    private Path excludePath;

    /**
     * Fully qualified name of the query implementation class.
     * This class must extend {@link org.apache.jackrabbit.core.query.AbstractQueryImpl}!
     */
    private final String queryImplClassName;

    /**
     * Task that checks if the query handler can be shut down because it
     * had been idle for {@link #idleTime} seconds.
     */
    private final TimerTask idleChecker;

    /**
     * Idle time in seconds. After the query handler had been idle for this
     * amount of time it is shut down. Defaults to -1 and causes the search
     * manager to never shut down.
     */
    private int idleTime;

    /**
     * Weakly references all {@link javax.jcr.query.Query} instances created
     * by this <code>SearchManager</code>.
     * If this map is empty and this search manager had been idle for at least
     * {@link #idleTime} seconds, then the query handler is shut down.
     */
    private final Map activeQueries = Collections.synchronizedMap(new WeakHashMap() {

    });

    /**
     * Creates a new <code>SearchManager</code>.
     *
     * @param config the search configuration.
     * @param nsReg            the namespace registry.
     * @param ntReg the node type registry.
     * @param itemMgr the shared item state manager.
     * @param rootNodeId     the id of the root node.
     * @param parentMgr        the parent search manager or <code>null</code> if
     *                         there is no parent search manager.
     * @param excludedNodeId id of the node that should be excluded from
     *                         indexing. Any descendant of that node will also
     *                         be excluded from indexing.
     * @throws RepositoryException if the search manager cannot be initialized
     */
    public SearchManager(SearchConfig config,
                         final NamespaceRegistry nsReg,
                         NodeTypeRegistry ntReg,
                         ItemStateManager itemMgr,
                         NodeId rootNodeId,
                         SearchManager parentMgr,
                         NodeId excludedNodeId) throws RepositoryException {
        if (config.getFileSystemConfig() != null) {
            fs = config.getFileSystemConfig().createFileSystem();
        } else {
            fs = null;
        }
        this.config = config;
        this.ntReg = ntReg;
        this.itemMgr = itemMgr;
        this.rootNodeId = rootNodeId;
        this.parentHandler = (parentMgr != null) ? parentMgr.handler : null;
        this.excludedNodeId = excludedNodeId;
        this.nsResolver = new AbstractNamespaceResolver() {
            public String getURI(String prefix) throws NamespaceException {
                try {
                    return nsReg.getURI(prefix);
                } catch (RepositoryException e) {
                    throw new NamespaceException(e.getMessage());
                }
            }

            public String getPrefix(String uri) throws NamespaceException {
                try {
                    return nsReg.getPrefix(uri);
                } catch (RepositoryException e) {
                    throw new NamespaceException(e.getMessage());
                }
            }
        };

        // register namespaces
        try {
            nsReg.getPrefix(NS_XS_URI);
        } catch (NamespaceException e) {
            // not yet known
            nsReg.registerNamespace(NS_XS_PREFIX, NS_XS_URI);
        }
        try {
            nsReg.getPrefix(NS_FN_URI);
        } catch (RepositoryException e) {
            // not yet known
            nsReg.registerNamespace(NS_FN_PREFIX, NS_FN_URI);
        }

        Properties params = config.getParameters();
        queryImplClassName = params.getProperty(PARAM_QUERY_IMPL, DEFAULT_QUERY_IMPL_CLASS);
        String idleTimeString = params.getProperty(PARAM_IDLE_TIME, String.valueOf(DEFAULT_IDLE_TIME));
        try {
            idleTime = Integer.decode(idleTimeString).intValue();
        } catch (NumberFormatException e) {
            idleTime = DEFAULT_IDLE_TIME;
        }

        if (excludedNodeId != null) {
            HierarchyManagerImpl hmgr = new HierarchyManagerImpl(rootNodeId, itemMgr, nsResolver);
            excludePath = hmgr.getPath(excludedNodeId);
        }

        // initialize query handler
        initializeQueryHandler();

        idleChecker = new TimerTask() {
            public void run() {
                if (lastAccess + (idleTime * 1000) < System.currentTimeMillis()) {
                    int inUse = activeQueries.size();
                    if (inUse == 0) {
                        try {
                            shutdownQueryHandler();
                        } catch (IOException e) {
                            log.warn("Unable to shutdown idle query handler", e);
                        }
                    } else {
                        log.debug("SearchManager is idle but " + inUse +
                                " queries are still in use.");
                    }
                }
            }
        };

        if (idleTime > -1) {
            IDLE_TIMER.schedule(idleChecker, 0, 1000);
        }
    }

    /**
     * Closes this <code>SearchManager</code> and also closes the
     * {@link FileSystem} configured in {@link SearchConfig}.
     */
    public void close() {
        try {
            idleChecker.cancel();
            shutdownQueryHandler();

            if (fs != null) {
                fs.close();
            }
        } catch (IOException e) {
            log.error("Exception closing QueryHandler.", e);
        } catch (FileSystemException e) {
            log.error("Exception closing FileSystem.", e);
        }
    }

    /**
     * Creates a query object that can be executed on the workspace.
     *
     * @param session   the session of the user executing the query.
     * @param itemMgr   the item manager of the user executing the query. Needed
     *                  to return <code>Node</code> instances in the result set.
     * @param statement the actual query statement.
     * @param language  the syntax of the query statement.
     * @return a <code>Query</code> instance to execute.
     * @throws InvalidQueryException if the query is malformed or the
     *                               <code>language</code> is unknown.
     * @throws RepositoryException   if any other error occurs.
     */
    public Query createQuery(SessionImpl session,
                             ItemManager itemMgr,
                             String statement,
                             String language)
            throws InvalidQueryException, RepositoryException {
        ensureInitialized();
        AbstractQueryImpl query = createQueryInstance();
        query.init(session, itemMgr, handler, statement, language);
        return query;
    }

    /**
     * Creates a query object from a node that can be executed on the workspace.
     *
     * @param session the session of the user executing the query.
     * @param itemMgr the item manager of the user executing the query. Needed
     *                to return <code>Node</code> instances in the result set.
     * @param node a node of type nt:query.
     * @return a <code>Query</code> instance to execute.
     * @throws InvalidQueryException if <code>absPath</code> is not a valid
     *                               persisted query (that is, a node of type nt:query)
     * @throws RepositoryException   if any other error occurs.
     */
    public Query createQuery(SessionImpl session,
                             ItemManager itemMgr,
                             Node node)
            throws InvalidQueryException, RepositoryException {
        ensureInitialized();
        AbstractQueryImpl query = createQueryInstance();
        query.init(session, itemMgr, handler, node);
        return query;
    }

    //---------------< EventListener interface >--------------------------------

    public void onEvent(EventIterator events) {
        log.debug("onEvent: indexing started");
        long time = System.currentTimeMillis();

        String exclude = "";
        if (excludePath != null) {
            try {
                exclude = excludePath.toJCRPath(nsResolver);
            } catch (NoPrefixDeclaredException e) {
                log.error("Error filtering events.", e);
            }
        }

        // nodes that need to be removed from the index.
        final Set removedNodes = new HashSet();
        // nodes that need to be added to the index.
        final Set addedNodes = new HashSet();
        // property events
        List propEvents = new ArrayList();

        while (events.hasNext()) {
            EventImpl e = (EventImpl) events.nextEvent();
            try {
                if (excludePath != null && e.getPath().startsWith(exclude)) {
                    continue;
                }
            } catch (RepositoryException ex) {
                log.error("Error filtering events.", ex);
            }
            long type = e.getType();
            if (type == Event.NODE_ADDED) {
                addedNodes.add(e.getChildId());
            } else if (type == Event.NODE_REMOVED) {
                removedNodes.add(e.getChildId());
            } else {
                propEvents.add(e);
            }
        }

        // sort out property events
        for (int i = 0; i < propEvents.size(); i++) {
            EventImpl event = (EventImpl) propEvents.get(i);
            NodeId nodeId = event.getParentId();
            if (event.getType() == Event.PROPERTY_ADDED) {
                if (addedNodes.add(nodeId)) {
                    // only property added
                    // need to re-index
                    removedNodes.add(nodeId);
                } else {
                    // the node where this prop belongs to is also new
                }
            } else if (event.getType() == Event.PROPERTY_CHANGED) {
                // need to re-index
                addedNodes.add(nodeId);
                removedNodes.add(nodeId);
            } else {
                // property removed event is only generated when node still exists
                addedNodes.add(nodeId);
                removedNodes.add(nodeId);
            }
        }

        NodeStateIterator addedStates = new NodeStateIterator() {
            private final Iterator iter = addedNodes.iterator();

            public void remove() {
                throw new UnsupportedOperationException();
            }

            public boolean hasNext() {
                return iter.hasNext();
            }

            public Object next() {
                return nextNodeState();
            }

            public NodeState nextNodeState() {
                NodeState item = null;
                NodeId id= (NodeId) iter.next();
                try {
                    item = (NodeState) itemMgr.getItemState(id);
                } catch (ItemStateException e) {
                    log.error("Unable to index node " + id + ": does not exist");
                }
                return item;
            }
        };
        NodeIdIterator removedIds = new NodeIdIterator() {
            private final Iterator iter = removedNodes.iterator();

            public NodeId nextNodeId() throws NoSuchElementException {
                return (NodeId) iter.next();
            }

            public void remove() {
                throw new UnsupportedOperationException();
            }

            public boolean hasNext() {
                return iter.hasNext();
            }

            public Object next() {
                return nextNodeId();
            }
        };

        if (removedNodes.size() > 0 || addedNodes.size() > 0) {
            try {
                ensureInitialized();
                handler.updateNodes(removedIds, addedStates);
            } catch (RepositoryException e) {
                log.error("Error indexing node.", e);
            } catch (IOException e) {
                log.error("Error indexing node.", e);
            }
        }

        if (log.isDebugEnabled()) {
            log.debug("onEvent: indexing finished in "
                    + String.valueOf(System.currentTimeMillis() - time)
                    + " ms.");
        }
    }

    /**
     * Creates a new instance of an {@link AbstractQueryImpl} which is not
     * initialized.
     *
     * @return an new query instance.
     * @throws RepositoryException if an error occurs while creating a new query
     *                             instance.
     */
    protected AbstractQueryImpl createQueryInstance() throws RepositoryException {
        try {
            Object obj = Class.forName(queryImplClassName).newInstance();
            if (obj instanceof AbstractQueryImpl) {
                // track query instances
                activeQueries.put(obj, null);
                return (AbstractQueryImpl) obj;
            } else {
                throw new IllegalArgumentException(queryImplClassName +
                        " is not of type " + AbstractQueryImpl.class.getName());
            }
        } catch (Throwable t) {
            throw new RepositoryException("Unable to create query: " + t.toString());
        }
    }

    //------------------------< internal >--------------------------------------

    /**
     * Initializes the query handler.
     *
     * @throws RepositoryException if the query handler cannot be initialized.
     */
    private void initializeQueryHandler() throws RepositoryException {
        // initialize query handler
        try {
            handler = (QueryHandler) config.newInstance();
            QueryHandlerContext context
                    = new QueryHandlerContext(fs, itemMgr, rootNodeId,
                            ntReg, parentHandler, excludedNodeId);
            handler.init(context);
        } catch (Exception e) {
            throw new RepositoryException(e.getMessage(), e);
        }
    }

    /**
     * Shuts down the query handler. If the query handler is already shut down
     * this method does nothing.
     *
     * @throws IOException if an error occurs while shutting down the query
     *                     handler.
     */
    private synchronized void shutdownQueryHandler() throws IOException {
        if (handler != null) {
            handler.close();
            handler = null;
        }
    }

    /**
     * Ensures that the query handler is initialized and updates the last
     * access to the current time.
     *
     * @throws RepositoryException if the query handler cannot be initialized.
     */
    private synchronized void ensureInitialized() throws RepositoryException {
        lastAccess = System.currentTimeMillis();
        if (handler == null) {
            initializeQueryHandler();
        }
    }
}
