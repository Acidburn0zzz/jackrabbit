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
package org.apache.jackrabbit.core.query.lucene;

import org.apache.jackrabbit.core.Constants;
import org.apache.jackrabbit.core.ItemManager;
import org.apache.jackrabbit.core.NodeId;
import org.apache.jackrabbit.core.QName;
import org.apache.jackrabbit.core.SessionImpl;
import org.apache.jackrabbit.core.nodetype.NodeTypeImpl;
import org.apache.jackrabbit.core.nodetype.PropertyDefinitionImpl;
import org.apache.jackrabbit.core.query.DefaultQueryNodeVisitor;
import org.apache.jackrabbit.core.query.ExecutableQuery;
import org.apache.jackrabbit.core.query.LocationStepQueryNode;
import org.apache.jackrabbit.core.query.NodeTypeQueryNode;
import org.apache.jackrabbit.core.query.OrderQueryNode;
import org.apache.jackrabbit.core.query.PropertyTypeRegistry;
import org.apache.jackrabbit.core.query.QueryParser;
import org.apache.jackrabbit.core.query.QueryRootNode;
import org.apache.jackrabbit.core.security.AccessManager;
import org.apache.log4j.Logger;
import org.apache.lucene.search.Hits;
import org.apache.lucene.search.Query;

import javax.jcr.RepositoryException;
import javax.jcr.nodetype.PropertyDefinition;
import javax.jcr.query.InvalidQueryException;
import javax.jcr.query.QueryResult;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Implements the {@link ExecutableQuery} interface.
 */
class QueryImpl implements ExecutableQuery {

    /**
     * The logger instance for this class
     */
    private static final Logger log = Logger.getLogger(QueryImpl.class);

    /**
     * The root node of the query tree
     */
    private final QueryRootNode root;

    /**
     * The session of the user executing this query
     */
    private final SessionImpl session;

    /**
     * The item manager of the user executing this query
     */
    private final ItemManager itemMgr;

    /**
     * The actual search index
     */
    private final SearchIndex index;

    /**
     * The property type registry for type lookup.
     */
    private final PropertyTypeRegistry propReg;

    /**
     * Creates a new query instance from a query string.
     *
     * @param session   the session of the user executing this query.
     * @param itemMgr   the item manager of the session executing this query.
     * @param index     the search index.
     * @param propReg   the property type registry.
     * @param statement the query statement.
     * @param language  the syntax of the query statement.
     * @throws InvalidQueryException if the query statement is invalid according
     *                               to the specified <code>language</code>.
     */
    public QueryImpl(SessionImpl session,
                     ItemManager itemMgr,
                     SearchIndex index,
                     PropertyTypeRegistry propReg,
                     String statement,
                     String language) throws InvalidQueryException {
        this.session = session;
        this.itemMgr = itemMgr;
        this.index = index;
        this.propReg = propReg;
        // parse query according to language
        // build query tree
        this.root = QueryParser.parse(statement, language, session.getNamespaceResolver());
    }

    /**
     * Executes this query and returns a <code>{@link QueryResult}</code>.
     *
     * @return a <code>QueryResult</code>
     * @throws RepositoryException if an error occurs
     */
    public QueryResult execute() throws RepositoryException {
        if (log.isDebugEnabled()) {
            log.debug("Executing query: \n" + root.dump());
        }
        // build lucene query
        Query query = LuceneQueryBuilder.createQuery(root, session,
                index.getContext().getItemStateManager(), index.getNamespaceMappings(),
                index.getAnalyzer(), propReg);

        OrderQueryNode orderNode = root.getOrderNode();

        OrderQueryNode.OrderSpec[] orderSpecs;
        if (orderNode != null) {
            orderSpecs = orderNode.getOrderSpecs();
        } else {
            orderSpecs = new OrderQueryNode.OrderSpec[0];
        }
        QName[] orderProperties = new QName[orderSpecs.length];
        boolean[] ascSpecs = new boolean[orderSpecs.length];
        for (int i = 0; i < orderSpecs.length; i++) {
            orderProperties[i] = orderSpecs[i].getProperty();
            ascSpecs[i] = orderSpecs[i].isAscending();
        }


        List uuids;
        List scores;
        AccessManager accessMgr = session.getAccessManager();

        // execute it
        try {
            Hits result = index.executeQuery(query, orderProperties, ascSpecs);
            uuids = new ArrayList(result.length());
            scores = new ArrayList(result.length());

            for (int i = 0; i < result.length(); i++) {
                String uuid = result.doc(i).get(FieldNames.UUID);
                // check access
                if (accessMgr.isGranted(new NodeId(uuid), AccessManager.READ)) {
                    uuids.add(uuid);
                    scores.add(new Float(result.score(i)));
                }
            }
        } catch (IOException e) {
            log.error("Exception while executing query: ", e);
            uuids = Collections.EMPTY_LIST;
            scores = Collections.EMPTY_LIST;
        }

        // get select properties
        QName[] selectProps = root.getSelectProperties();
        if (selectProps.length == 0) {
            // use node type constraint
            LocationStepQueryNode[] steps = root.getLocationNode().getPathSteps();
            final QName[] ntName = new QName[1];
            steps[steps.length - 1].acceptOperands(new DefaultQueryNodeVisitor() {
                public Object visit(NodeTypeQueryNode node, Object data) {
                    ntName[0] = node.getValue();
                    return data;
                }
            }, null);
            if (ntName[0] == null) {
                ntName[0] = Constants.NT_BASE;
            }
            NodeTypeImpl nt = session.getNodeTypeManager().getNodeType(ntName[0]);
            PropertyDefinition[] propDefs = nt.getPropertyDefinitions();
            List tmp = new ArrayList();
            for (int i = 0; i < propDefs.length; i++) {
                if (!propDefs[i].isMultiple()) {
                    tmp.add(((PropertyDefinitionImpl) propDefs[i]).getQName());
                }
            }
            selectProps = (QName[]) tmp.toArray(new QName[tmp.size()]);
        }

        // return QueryResult
        return new QueryResultImpl(itemMgr,
                (String[]) uuids.toArray(new String[uuids.size()]),
                (Float[]) scores.toArray(new Float[scores.size()]),
                selectProps,
                session.getNamespaceResolver(),
                orderNode == null);
    }
}
