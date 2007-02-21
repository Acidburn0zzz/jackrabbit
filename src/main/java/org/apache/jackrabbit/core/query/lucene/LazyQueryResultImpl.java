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
package org.apache.jackrabbit.core.query.lucene;

import org.apache.jackrabbit.core.ItemManager;
import org.apache.jackrabbit.core.NodeId;
import org.apache.jackrabbit.core.NodeImpl;
import org.apache.jackrabbit.core.security.AccessManager;
import org.apache.jackrabbit.name.NamespaceResolver;
import org.apache.jackrabbit.name.NoPrefixDeclaredException;
import org.apache.jackrabbit.name.QName;
import org.apache.jackrabbit.name.NameFormat;
import org.apache.lucene.search.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.NodeIterator;
import javax.jcr.RepositoryException;
import javax.jcr.ItemNotFoundException;
import javax.jcr.Node;
import javax.jcr.query.QueryResult;
import javax.jcr.query.RowIterator;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * Implements the <code>javax.jcr.query.QueryResult</code> interface.
 */
public class LazyQueryResultImpl implements QueryResult {

    /**
     * The logger instance for this class
     */
    private static final Logger log = LoggerFactory.getLogger(QueryResultImpl.class);

    /**
     * The search index to execute the query.
     */
    private final SearchIndex index;

    /**
     * The item manager of the session executing the query
     */
    private final ItemManager itemMgr;

    /**
     * The namespace resolver of the session executing the query
     */
    protected final NamespaceResolver resolver;

    /**
     * The access manager of the session that executes the query.
     */
    private final AccessManager accessMgr;

    /**
     * The query instance which created this query result.
     */
    protected final QueryImpl queryImpl;

    /**
     * The lucene query to execute.
     */
    protected final Query query;

    /**
     * The select properties
     */
    protected final QName[] selectProps;

    /**
     * The names of properties to use for ordering the result set.
     */
    protected final QName[] orderProps;

    /**
     * The order specifier for each of the order properties.
     */
    protected final boolean[] orderSpecs;

    /**
     * The result nodes including their score. This list is populated on a lazy
     * basis while a client iterates through the results.
     */
    private final List resultNodes = new ArrayList();

    /**
     * This is the raw number of results that matched the query. This number
     * also includes matches which will not be returned due to access
     * restrictions. This value is set when the query is executed the first
     * time.
     */
    private int numResults = -1;

    /**
     * The number of results that are invalid, either because a node does not
     * exist anymore or because the session does not have access to the node.
     */
    private int invalid = 0;

    /**
     * If <code>true</code> nodes are returned in document order.
     */
    private final boolean docOrder;

    /**
     * Creates a new query result.
     *
     * @param index         the search index where the query is executed.
     * @param itemMgr       the item manager of the session executing the
     *                      query.
     * @param resolver      the namespace resolver of the session executing the
     *                      query.
     * @param accessMgr     the access manager of the session executiong the
     *                      query.
     * @param queryImpl     the query instance which created this query result.
     * @param query         the lucene query to execute on the index.
     * @param selectProps   the select properties of the query.
     * @param orderProps    the names of the order properties.
     * @param orderSpecs    the order specs, one for each order property name.
     * @param documentOrder if <code>true</code> the result is returned in
     *                      document order.
     */
    public LazyQueryResultImpl(SearchIndex index,
                           ItemManager itemMgr,
                           NamespaceResolver resolver,
                           AccessManager accessMgr,
                           QueryImpl queryImpl,
                           Query query,
                           QName[] selectProps,
                           QName[] orderProps,
                           boolean[] orderSpecs,
                           boolean documentOrder) throws RepositoryException {
        this.index = index;
        this.itemMgr = itemMgr;
        this.resolver = resolver;
        this.accessMgr = accessMgr;
        this.queryImpl = queryImpl;
        this.query = query;
        this.selectProps = selectProps;
        this.orderProps = orderProps;
        this.orderSpecs = orderSpecs;
        this.docOrder = orderProps.length == 0 && documentOrder;
        // if document order is requested get all results right away
        getResults(docOrder ? Integer.MAX_VALUE : index.getResultFetchSize());
    }

    /**
     * {@inheritDoc}
     */
    public String[] getColumnNames() throws RepositoryException {
        try {
            String[] propNames = new String[selectProps.length];
            for (int i = 0; i < selectProps.length; i++) {
                propNames[i] = NameFormat.format(selectProps[i], resolver);
            }
            return propNames;
        } catch (NoPrefixDeclaredException npde) {
            String msg = "encountered invalid property name";
            log.debug(msg);
            throw new RepositoryException(msg, npde);

        }
    }

    /**
     * {@inheritDoc}
     */
    public NodeIterator getNodes() throws RepositoryException {
        return getNodeIterator();
    }

    /**
     * {@inheritDoc}
     */
    public RowIterator getRows() throws RepositoryException {
        return new RowIteratorImpl(getNodeIterator(), selectProps, resolver);
    }

    /**
     * Executes the query for this result and returns hits. The caller must
     * close the query hits when he is done using it.
     *
     * @return hits for this query result.
     * @throws IOException if an error occurs while executing the query.
     */
    protected QueryHits executeQuery() throws IOException {
        return index.executeQuery(queryImpl, query, orderProps, orderSpecs);
    }

    //--------------------------------< internal >------------------------------

    /**
     * Creates a node iterator over the result nodes.
     *
     * @return a node iterator over the result nodes.
     */
    private ScoreNodeIterator getNodeIterator() {
        if (docOrder) {
            return new DocOrderNodeIteratorImpl(itemMgr, resultNodes);
        } else {
            return new LazyScoreNodeIterator();
        }
    }

    /**
     * Attempts to get <code>size</code> results and puts them into {@link
     * #resultNodes}. If the size of {@link #resultNodes} is less than
     * <code>size</code> then there are no more than <code>resultNodes.size()</code>
     * results for this query.
     *
     * @param size the number of results to fetch for the query.
     * @throws RepositoryException if an error occurs while executing the
     *                             query.
     */
    private void getResults(int size) throws RepositoryException {
        if (log.isDebugEnabled()) {
            log.debug("getResults(" + size + ")");
        }
        if (resultNodes.size() >= size) {
            // we already have them all
            return;
        }

        // execute it
        QueryHits result = null;
        try {
            result = executeQuery();

            // set num results with the first query execution
            if (numResults == -1) {
                numResults = result.length();
            }

            int start = resultNodes.size() + invalid;
            int max = Math.min(result.length(), numResults);
            for (int i = start; i < max && resultNodes.size() < size; i++) {
                NodeId id = NodeId.valueOf(result.doc(i).get(FieldNames.UUID));
                // check access
                try {
                    if (accessMgr.isGranted(id, AccessManager.READ)) {
                        resultNodes.add(new ScoreNode(id, result.score(i)));
                    } else {
                        invalid++;
                    }
                } catch (ItemNotFoundException e) {
                    // has been deleted meanwhile
                    invalid++;
                }
            }
        } catch (IOException e) {
            log.error("Exception while executing query: ", e);
            // todo throw?
        } finally {
            if (result != null) {
                try {
                    result.close();
                } catch (IOException e) {
                    log.warn("Unable to close query result: " + e);
                }
            }
        }
    }

    private final class LazyScoreNodeIterator implements ScoreNodeIterator {

        private int position = -1;

        private boolean initialized = false;

        private NodeImpl next;

        /**
         * {@inheritDoc}
         */
        public float getScore() {
            initialize();
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            return ((ScoreNode) resultNodes.get(position)).getScore();
        }

        /**
         * {@inheritDoc}
         */
        public NodeImpl nextNodeImpl() {
            initialize();
            if (next == null) {
                throw new NoSuchElementException();
            }
            NodeImpl n = next;
            fetchNext();
            return n;
        }

        /**
         * {@inheritDoc}
         */
        public Node nextNode() {
            return nextNodeImpl();
        }

        /**
         * {@inheritDoc}
         */
        public void skip(long skipNum) {
            initialize();
            if (skipNum < 0) {
                throw new IllegalArgumentException("skipNum must not be negative");
            }
            if ((position + invalid + skipNum) > numResults) {
                throw new NoSuchElementException();
            }
            if (skipNum == 0) {
                // do nothing
            } else {
                // attempt to get enough results
                try {
                    getResults(position + invalid + (int) skipNum);
                    if (resultNodes.size() >= position + skipNum) {
                        // skip within already fetched results
                        position += skipNum - 1;
                        fetchNext();
                    } else {
                        // not enough results after getResults()
                        throw new NoSuchElementException();
                    }
                } catch (RepositoryException e) {
                    throw new NoSuchElementException(e.getMessage());
                }
            }
        }

        /**
         * {@inheritDoc}
         * <p/>
         * This value may shrink when the query result encounters non-existing
         * nodes or the session does not have access to a node.
         */
        public long getSize() {
            return numResults - invalid;
        }

        /**
         * {@inheritDoc}
         */
        public long getPosition() {
            initialize();
            return position;
        }

        /**
         * @throws UnsupportedOperationException always.
         */
        public void remove() {
            throw new UnsupportedOperationException("remove");
        }

        /**
         * {@inheritDoc}
         */
        public boolean hasNext() {
            initialize();
            return next != null;
        }

        /**
         * {@inheritDoc}
         */
        public Object next() {
            return nextNodeImpl();
        }

        /**
         * Initializes this iterator but only if it is not yet initialized.
         */
        private void initialize() {
            if (!initialized) {
                fetchNext();
                initialized = true;
            }
        }

        /**
         * Fetches the next node to return by this iterator. If this method
         * returns and {@link #next} is <code>null</code> then there is no next
         * node.
         */
        private void fetchNext() {
            next = null;
            int nextPos = position + 1;
            while (next == null && (nextPos + invalid) < numResults) {
                if (nextPos >= resultNodes.size()) {
                    // fetch more results
                    try {
                        int num;
                        if (resultNodes.size() == 0) {
                            num = index.getResultFetchSize();
                        } else {
                            num = resultNodes.size() * 2;
                        }
                        getResults(num);
                    } catch (RepositoryException e) {
                        log.warn("Exception getting more results: " + e);
                    }
                    // check again
                    if (nextPos >= resultNodes.size()) {
                        // no more valid results
                        return;
                    }
                }
                ScoreNode sn = (ScoreNode) resultNodes.get(nextPos);
                try {
                    next = (NodeImpl) itemMgr.getItem(sn.getNodeId());
                } catch (RepositoryException e) {
                    log.warn("Exception retrieving Node with UUID: "
                            + sn.getNodeId() + ": " + e.toString());
                    // remove score node and try next
                    resultNodes.remove(nextPos);
                    invalid++;
                }
            }
            position++;
        }
    }
}
