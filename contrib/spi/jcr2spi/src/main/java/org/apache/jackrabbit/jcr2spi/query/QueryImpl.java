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
package org.apache.jackrabbit.jcr2spi.query;

import org.apache.jackrabbit.jcr2spi.ItemManager;
import org.apache.jackrabbit.jcr2spi.WorkspaceManager;
import org.apache.jackrabbit.jcr2spi.state.ItemStateManager;
import org.apache.jackrabbit.name.MalformedPathException;
import org.apache.jackrabbit.name.NamespaceResolver;
import org.apache.jackrabbit.name.NoPrefixDeclaredException;
import org.apache.jackrabbit.name.Path;
import org.apache.jackrabbit.name.PathFormat;
import org.apache.jackrabbit.name.QName;
import org.apache.jackrabbit.name.NameFormat;
import org.apache.jackrabbit.spi.QueryInfo;

import javax.jcr.ItemExistsException;
import javax.jcr.ItemNotFoundException;
import javax.jcr.Node;
import javax.jcr.PathNotFoundException;
import javax.jcr.RepositoryException;
import javax.jcr.UnsupportedRepositoryOperationException;
import javax.jcr.Session;
import javax.jcr.lock.LockException;
import javax.jcr.nodetype.ConstraintViolationException;
import javax.jcr.query.InvalidQueryException;
import javax.jcr.query.Query;
import javax.jcr.query.QueryResult;
import javax.jcr.version.VersionException;

/**
 * Provides the default implementation for a JCR query.
 */
public class QueryImpl implements Query {

    /**
     * The session of the user executing this query
     */
    private final Session session;

    /**
     * The namespace nsResolver of the session that executes this query.
     */
    // DIFF JR: added
    private final NamespaceResolver nsResolver;

    /**
     * The item manager of the session that executes this query.
     */
    private final ItemManager itemManager;

    /**
     * The item state manager of the session that executes this query.
     */
    private final ItemStateManager itemStateManager;

    /**
     * The query statement
     */
    private String statement;

    /**
     * The syntax of the query statement
     */
    private String language;

    /**
     * The node where this query is persisted. Only set when this is a persisted
     * query.
     */
    private Node node;

    /**
     * The query handler for this query.
     */
    // DIFF JR: use WorkspaceManager (-> RepositoryService) instead
    //protected QueryHandler handler;
    private WorkspaceManager wspManager;

    /**
     * Creates a new query.
     *
     * @param session    the session that created this query.
     * @param nsResolver the namespace resolver to be used.
     * @param itemMgr    the item manager of that session.
     * @param itemStateManager the item state manager of that session.
     * @param wspManager the workspace manager that belongs to the session.
     * @param statement  the query statement.
     * @param language   the language of the query statement.
     * @throws InvalidQueryException if the query is invalid.
     */
    // DIFF JR: uses WorkspaceManager instead of QueryHandler
    public QueryImpl(Session session, NamespaceResolver nsResolver,
                     ItemManager itemMgr, ItemStateManager itemStateManager,
                     WorkspaceManager wspManager,
                     String statement, String language) throws InvalidQueryException {
        this.session = session;
        this.nsResolver = nsResolver;
        this.itemManager = itemMgr;
        this.itemStateManager = itemStateManager;
        this.statement = statement;
        this.language = language;
        this.wspManager = wspManager;
        // DIFF JR: todo validate statement
        //this.query = handler.createExecutableQuery(session, itemMgr, statement, language);
    }

    /**
     * Creates a query from a node.
     *
     * @param session    the session that created this query.
     * @param nsResolver the namespace resolver to be used.
     * @param itemMgr    the item manager of that session.
     * @param wspManager the workspace manager that belongs to the session.
     * @param node       the node from where to read the query.
     * @throws InvalidQueryException if the query is invalid.
     * @throws RepositoryException   if another error occurs while reading from
     *                               the node.
     */
    // DIFF JR: uses WorkspaceManager instead of QueryHandler
    public QueryImpl(Session session, NamespaceResolver nsResolver,
                     ItemManager itemMgr, ItemStateManager itemStateManager,
                     WorkspaceManager wspManager, Node node)
        throws InvalidQueryException, RepositoryException {

        this.session = session;
        this.nsResolver = nsResolver;
        this.itemManager = itemMgr;
        this.itemStateManager = itemStateManager;
        this.node = node;
        this.wspManager = wspManager;

        try {
            if (!node.isNodeType(NameFormat.format(QName.NT_QUERY, nsResolver))) {
                throw new InvalidQueryException("Node is not of type nt:query");
            }
            statement = node.getProperty(NameFormat.format(QName.JCR_STATEMENT, nsResolver)).getString();
            language = node.getProperty(NameFormat.format(QName.JCR_LANGUAGE, nsResolver)).getString();
            // DIFF JR: todo validate statement
            //query = handler.createExecutableQuery(session, itemMgr, statement, language);
        } catch (NoPrefixDeclaredException e) {
            throw new RepositoryException(e.getMessage(), e);
        }
    }

    /**
     * @see Query#execute() 
     */
    public QueryResult execute() throws RepositoryException {
        QueryInfo qI = wspManager.executeQuery(statement, language);
        return new QueryResultImpl(itemManager, itemStateManager, qI, nsResolver);
    }

    /**
     * @see Query#getStatement()
     */
    public String getStatement() {
        return statement;
    }

    /**
     * @see Query#getLanguage()
     */
    public String getLanguage() {
        return language;
    }

    /**
     * @see Query#getStoredQueryPath()
     */
    public String getStoredQueryPath() throws ItemNotFoundException, RepositoryException {
        if (node == null) {
            throw new ItemNotFoundException("Not a persistent query.");
        }
        return node.getPath();
    }

    /**
     * @see Query#storeAsNode(String)
     */
    public Node storeAsNode(String absPath) throws ItemExistsException,
        PathNotFoundException, VersionException, ConstraintViolationException,
        LockException, UnsupportedRepositoryOperationException, RepositoryException {

        try {
            Path p = PathFormat.parse(absPath, nsResolver).getNormalizedPath();
            if (!p.isAbsolute()) {
                throw new RepositoryException(absPath + " is not an absolute path");
            }
            if (session.itemExists(absPath)) {
                throw new ItemExistsException(absPath);
            }
            String jcrParent = PathFormat.format(p.getAncestor(1), nsResolver);
            if (!session.itemExists(jcrParent)) {
                throw new PathNotFoundException(jcrParent);
            }
            String relPath = PathFormat.format(p, nsResolver).substring(1);
            String ntName = NameFormat.format(QName.NT_QUERY, nsResolver);
            Node queryNode = session.getRootNode().addNode(relPath, ntName);
            // set properties
            queryNode.setProperty(NameFormat.format(QName.JCR_LANGUAGE, nsResolver), language);
            queryNode.setProperty(NameFormat.format(QName.JCR_STATEMENT, nsResolver), statement);
            node = queryNode;
            return node;
        } catch (MalformedPathException e) {
            throw new RepositoryException(e.getMessage(), e);
        } catch (NoPrefixDeclaredException e) {
            throw new RepositoryException(e.getMessage(), e);
        }
    }
}

