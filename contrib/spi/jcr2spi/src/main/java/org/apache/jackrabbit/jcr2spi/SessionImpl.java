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
package org.apache.jackrabbit.jcr2spi;

import org.apache.jackrabbit.jcr2spi.nodetype.NodeTypeManagerImpl;
import org.apache.jackrabbit.jcr2spi.security.SecurityConstants;
import org.apache.jackrabbit.jcr2spi.security.AccessManager;
import org.apache.jackrabbit.jcr2spi.state.SessionItemStateManager;
import org.apache.jackrabbit.jcr2spi.state.ItemStateManager;
import org.apache.jackrabbit.jcr2spi.state.UpdatableItemStateManager;
import org.apache.jackrabbit.jcr2spi.state.ItemStateValidator;
import org.apache.jackrabbit.jcr2spi.state.ItemState;
import org.apache.jackrabbit.jcr2spi.state.NoSuchItemStateException;
import org.apache.jackrabbit.jcr2spi.state.ItemStateException;
import org.apache.jackrabbit.jcr2spi.state.NodeState;
import org.apache.jackrabbit.jcr2spi.xml.DocViewSAXEventGenerator;
import org.apache.jackrabbit.jcr2spi.xml.SysViewSAXEventGenerator;
import org.apache.jackrabbit.jcr2spi.xml.ImportHandler;
import org.apache.jackrabbit.jcr2spi.xml.SessionImporter;
import org.apache.jackrabbit.jcr2spi.xml.Importer;
import org.apache.jackrabbit.jcr2spi.lock.LockManager;
import org.apache.jackrabbit.jcr2spi.version.VersionManager;
import org.apache.jackrabbit.jcr2spi.operation.Move;
import org.apache.jackrabbit.jcr2spi.operation.Operation;
import org.apache.jackrabbit.jcr2spi.name.LocalNamespaceMappings;
import org.apache.jackrabbit.jcr2spi.config.RepositoryConfig;
import org.apache.jackrabbit.name.MalformedPathException;
import org.apache.jackrabbit.name.NamespaceResolver;
import org.apache.jackrabbit.name.QName;
import org.apache.jackrabbit.name.Path;
import org.apache.jackrabbit.name.PathFormat;
import org.apache.jackrabbit.name.NameFormat;
import org.apache.jackrabbit.name.NoPrefixDeclaredException;
import org.apache.jackrabbit.spi.RepositoryService;
import org.apache.jackrabbit.spi.SessionInfo;
import org.apache.jackrabbit.spi.NodeId;
import org.apache.jackrabbit.spi.IdFactory;
import org.apache.jackrabbit.spi.XASessionInfo;
import org.apache.commons.collections.map.ReferenceMap;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;
import org.xml.sax.ContentHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.ErrorHandler;
import org.xml.sax.helpers.XMLReaderFactory;

import javax.jcr.AccessDeniedException;
import javax.jcr.Credentials;
import javax.jcr.InvalidItemStateException;
import javax.jcr.InvalidSerializedDataException;
import javax.jcr.Item;
import javax.jcr.ItemExistsException;
import javax.jcr.ItemNotFoundException;
import javax.jcr.LoginException;
import javax.jcr.NamespaceException;
import javax.jcr.NoSuchWorkspaceException;
import javax.jcr.Node;
import javax.jcr.PathNotFoundException;
import javax.jcr.Repository;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.SimpleCredentials;
import javax.jcr.UnsupportedRepositoryOperationException;
import javax.jcr.ValueFactory;
import javax.jcr.Workspace;
import javax.jcr.lock.LockException;
import javax.jcr.nodetype.ConstraintViolationException;
import javax.jcr.version.VersionException;
import javax.xml.transform.sax.SAXTransformerFactory;
import javax.xml.transform.sax.TransformerHandler;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.AccessControlException;
import java.util.Map;
import java.util.HashMap;

/**
 * <code>SessionImpl</code>...
 */
public class SessionImpl implements Session, ManagerProvider {

    private static Logger log = LoggerFactory.getLogger(SessionImpl.class);

    private boolean alive;

    /**
     * the attributes of this session
     */
    private final HashMap attributes = new HashMap();
    /**
     * Listeners (weak references)
     */
    private final Map listeners = new ReferenceMap(ReferenceMap.WEAK, ReferenceMap.WEAK);

    private final Repository repository;
    private final RepositoryConfig config;
    private final WorkspaceImpl workspace;

    private final SessionInfo sessionInfo;

    private final LocalNamespaceMappings nsMappings;
    private final NodeTypeManagerImpl ntManager;

    private final SessionItemStateManager itemStateManager;
    private final ItemManager itemManager;
    private final ItemStateValidator validator;

    SessionImpl(SessionInfo sessionInfo, Repository repository, RepositoryConfig config)
        throws RepositoryException {

        alive = true;
        this.repository = repository;
        this.config = config;
        this.sessionInfo = sessionInfo;

        workspace = createWorkspaceInstance(config.getRepositoryService(), sessionInfo);

        // build local name-mapping
        nsMappings = new LocalNamespaceMappings(workspace.getNamespaceRegistryImpl());

        // build nodetype manager
        ntManager = new NodeTypeManagerImpl(workspace.getNodeTypeRegistry(), getNamespaceResolver(), getValueFactory());

        validator = new ItemStateValidator(workspace.getNodeTypeRegistry(), this);

        // build the state mananger
        itemStateManager = createSessionItemStateManager(workspace.getUpdatableItemStateManager(), nsMappings);

        itemManager = createItemManager(getHierarchyManager());
    }

    //--------------------------------------------------< Session interface >---
    /**
     * @see javax.jcr.Session#getRepository()
     */
    public Repository getRepository() {
        return repository;
    }

    /**
     * @see javax.jcr.Session#getUserID()
     */
    public String getUserID() {
        return sessionInfo.getUserID();
    }

    /**
     * @see javax.jcr.Session#getAttribute(String)
     */
    public Object getAttribute(String name) {
        return attributes.get(name);
    }

    /**
     * @see javax.jcr.Session#getAttributeNames()
     */
    public String[] getAttributeNames() {
        return (String[]) attributes.keySet().toArray(new String[attributes.size()]);

    }

    /**
     * @see javax.jcr.Session#getWorkspace()
     */
    public Workspace getWorkspace() {
        return workspace;
    }

    /**
     * @see javax.jcr.Session#impersonate(Credentials)
     */
    public Session impersonate(Credentials credentials) throws LoginException, RepositoryException {
        checkIsAlive();
        // TODO: check whether restriction to SimpleCredentials is correct
        if (!(credentials instanceof SimpleCredentials)) {
            String msg = "impersonate failed: incompatible credentials, SimpleCredentials expected";
            log.debug(msg);
            throw new RepositoryException(msg);
        }

        // set IMPERSONATOR_ATTRIBUTE attribute of given credentials
        // with current session
        SimpleCredentials creds = (SimpleCredentials) credentials;
        creds.setAttribute(SecurityConstants.IMPERSONATOR_ATTRIBUTE, this);

        try {
            return repository.login(credentials, getWorkspace().getName());
        } catch (NoSuchWorkspaceException nswe) {
            // should never get here...
            String msg = "impersonate failed";
            log.error(msg, nswe);
            throw new RepositoryException(msg, nswe);
        } finally {
            // make sure IMPERSONATOR_ATTRIBUTE is removed
            creds.removeAttribute(SecurityConstants.IMPERSONATOR_ATTRIBUTE);
        }
    }

    /**
     * @see javax.jcr.Session#getRootNode()
     */
    public Node getRootNode() throws RepositoryException {
        checkIsAlive();
        try {
            ItemState state = getItemStateManager().getRootState();
            return (Node) itemManager.getItem(state);
        } catch (ItemStateException e) {
            String msg = "Failed to retrieve root node.";
            log.error(msg, e);
            throw new RepositoryException(msg, e);
        }
    }

    /**
     * @see javax.jcr.Session#getNodeByUUID(String)
     */
    public Node getNodeByUUID(String uuid) throws ItemNotFoundException, RepositoryException {
        // sanity check performed by getNodeById
        Node node = getNodeById(getIdFactory().createNodeId(uuid));
        if (node instanceof NodeImpl && ((NodeImpl)node).isNodeType(QName.MIX_REFERENCEABLE)) {
            return node;
        } else {
            // fall back
            try {
                String mixReferenceable = NameFormat.format(QName.MIX_REFERENCEABLE, getNamespaceResolver());
                if (node.isNodeType(mixReferenceable)) {
                    return node;
                }
            } catch (NoPrefixDeclaredException e) {
                // should not occur.
                throw new RepositoryException(e);
            }
            // there is a node with that uuid but the node does not expose it
            throw new ItemNotFoundException(uuid);
        }
    }

    /**
     * Retrieve the <code>Node</code> with the given id.
     *
     * @param id
     * @return node with the given <code>NodeId</code>.
     * @throws ItemNotFoundException if no such node exists or if this
     * <code>Session</code> does not have permission to access the node.
     * @throws RepositoryException
     */
    private Node getNodeById(NodeId id) throws ItemNotFoundException, RepositoryException {
        // check sanity of this session
        checkIsAlive();
        try {
            ItemState state = getItemStateManager().getItemState(id);
            Item item = getItemManager().getItem(state);
            if (item.isNode()) {
                return (Node) item;
            } else {
                log.error("NodeId '" + id + " does not point to a Node");
                throw new ItemNotFoundException(id.toString());
            }
        } catch (AccessDeniedException ade) {
            throw new ItemNotFoundException(id.toString());
        } catch (NoSuchItemStateException e) {
            throw new ItemNotFoundException(id.toString());
        } catch (ItemStateException e) {
            String msg = "Failed to retrieve item state of item " + id;
            log.error(msg, e);
            throw new RepositoryException(msg, e);
        }
    }

    /**
     * @see javax.jcr.Session#getItem(String)
     */
    public Item getItem(String absPath) throws PathNotFoundException, RepositoryException {
        checkIsAlive();
        try {
            Path qPath = getQPath(absPath);
            return getItemManager().getItem(qPath.getNormalizedPath());
        } catch (AccessDeniedException ade) {
            throw new PathNotFoundException(absPath);
        } catch (MalformedPathException e) {
            throw new RepositoryException(e);
        }
    }

    /**
     * @see javax.jcr.Session#itemExists(String)
     */
    public boolean itemExists(String absPath) throws RepositoryException {
        checkIsAlive();
        try {
            Path qPath = getQPath(absPath);
            return getItemManager().itemExists(qPath.getNormalizedPath());
        } catch (MalformedPathException e) {
            throw new RepositoryException(e);
        }
    }

    /**
     * @see javax.jcr.Session#move(String, String)
     */
    public void move(String srcAbsPath, String destAbsPath) throws ItemExistsException, PathNotFoundException, VersionException, ConstraintViolationException, LockException, RepositoryException {
        checkSupportedOption(Repository.LEVEL_2_SUPPORTED);
        checkIsAlive();

        // retrieve qualified paths
        Path srcPath = getQPath(srcAbsPath);
        Path destPath = getQPath(destAbsPath);

        // all validation is performed by Move Operation and state-manager
        Operation op = Move.create(srcPath, destPath, getHierarchyManager(), getNamespaceResolver());
        itemStateManager.execute(op);
    }

    /**
     * @see javax.jcr.Session#save()
     */
    public void save() throws AccessDeniedException, ConstraintViolationException, InvalidItemStateException, VersionException, LockException, RepositoryException {
        checkSupportedOption(Repository.LEVEL_2_SUPPORTED);
        // delegate to the root node (including check for isAlive)
        getRootNode().save();
    }

    /**
     * @see javax.jcr.Session#refresh(boolean)
     */
    public void refresh(boolean keepChanges) throws RepositoryException {
        // delegate to the root node (including check for isAlive)
        getRootNode().refresh(keepChanges);
    }

    /**
     * @see javax.jcr.Session#hasPendingChanges()
     */
    public boolean hasPendingChanges() throws RepositoryException {
        checkIsAlive();
        return itemStateManager.hasPendingChanges();
    }

    /**
     * @see javax.jcr.Session#getValueFactory()
     */
    public ValueFactory getValueFactory() throws UnsupportedRepositoryOperationException, RepositoryException {
        // must throw UnsupportedRepositoryOperationException if writing is
        // not supported
        checkSupportedOption(Repository.LEVEL_2_SUPPORTED);
        return config.getValueFactory();
    }

    /**
     * @see javax.jcr.Session#checkPermission(String, String)
     */
    public void checkPermission(String absPath, String actions) throws AccessControlException, RepositoryException {
        checkIsAlive();
        // build the array of actions to be checked
        String[] actionsArr = actions.split(",");

        Path targetPath = getQPath(absPath);

        boolean isGranted;
        // The given abs-path may point to a non-existing item
        if (itemExists(absPath)) {
            ItemState itemState = getHierarchyManager().getItemState(targetPath);
            isGranted = getAccessManager().isGranted(itemState, actionsArr);
        } else {
            NodeState parentState = null;
            Path parentPath = targetPath;
            while (parentState == null) {
                parentPath = parentPath.getAncestor(1);
                if (itemManager.itemExists(parentPath)) {
                    ItemState itemState = getHierarchyManager().getItemState(parentPath);
                    if (itemState.isNode()) {
                        parentState = (NodeState) itemState;
                    }
                }
            }
            // parentState is the nearest existing nodeState or the root state.
            try {
                Path relPath = parentPath.computeRelativePath(targetPath);
                isGranted = getAccessManager().isGranted(parentState, relPath, actionsArr);
            } catch (MalformedPathException e) {
                // should not occurs
                throw new RepositoryException(e);
            }
        }

        if (!isGranted) {
            throw new AccessControlException("Access control violation: path = " + absPath + ", actions = " + actions);
        }
    }

    /**
     * @see Session#getImportContentHandler(String, int)
     */
    public ContentHandler getImportContentHandler(String parentAbsPath, int uuidBehavior) throws PathNotFoundException, ConstraintViolationException, VersionException, LockException, RepositoryException {
        checkSupportedOption(Repository.LEVEL_2_SUPPORTED);
        checkIsAlive();

        Path parentPath = getQPath(parentAbsPath);
        // NOTE: check if path corresponds to Node and is writable is performed
        // within the SessionImporter.
        Importer importer = new SessionImporter(parentPath, this, itemStateManager, uuidBehavior);
        return new ImportHandler(importer, getNamespaceResolver(), workspace.getNamespaceRegistry());
    }

    /**
     * @see javax.jcr.Session#importXML(String, java.io.InputStream, int)
     */
    public void importXML(String parentAbsPath, InputStream in, int uuidBehavior) throws IOException, PathNotFoundException, ItemExistsException, ConstraintViolationException, VersionException, InvalidSerializedDataException, LockException, RepositoryException {
        // NOTE: checks are performed by 'getImportContentHandler'
        ContentHandler handler = getImportContentHandler(parentAbsPath, uuidBehavior);
        try {
            XMLReader parser = XMLReaderFactory.createXMLReader("org.apache.xerces.parsers.SAXParser");
            parser.setContentHandler(handler);
            if (handler instanceof ErrorHandler) {
                parser.setErrorHandler((ErrorHandler)handler);
            }
            // being paranoid...
            parser.setFeature("http://xml.org/sax/features/namespaces", true);
            parser.setFeature("http://xml.org/sax/features/namespace-prefixes", false);
            parser.parse(new InputSource(in));
        } catch (SAXException se) {
            // check for wrapped repository exception
            Exception e = se.getException();
            if (e != null && e instanceof RepositoryException) {
                throw (RepositoryException) e;
            } else {
                String msg = "failed to parse XML stream";
                log.debug(msg);
                throw new InvalidSerializedDataException(msg, se);
            }
        }
    }

    /**
     * @see javax.jcr.Session#exportSystemView(String, org.xml.sax.ContentHandler, boolean, boolean)
     */
    public void exportSystemView(String absPath, ContentHandler contentHandler, boolean skipBinary, boolean noRecurse) throws PathNotFoundException, SAXException, RepositoryException {
        checkIsAlive();
        Item item = getItem(absPath);
        if (!item.isNode()) {
            // a property instead of a node exists at the specified path
            throw new PathNotFoundException(absPath);
        }
        new SysViewSAXEventGenerator((Node)item, noRecurse, skipBinary, contentHandler).serialize();
    }

    /**
     * @see javax.jcr.Session#exportSystemView(String, OutputStream, boolean, boolean)
     */
    public void exportSystemView(String absPath, OutputStream out, boolean skipBinary, boolean noRecurse) throws IOException, PathNotFoundException, RepositoryException {
        SAXTransformerFactory stf = (SAXTransformerFactory) SAXTransformerFactory.newInstance();
        try {
            TransformerHandler th = stf.newTransformerHandler();
            th.setResult(new StreamResult(out));
            th.getTransformer().setParameter(OutputKeys.METHOD, "xml");
            th.getTransformer().setParameter(OutputKeys.ENCODING, "UTF-8");
            th.getTransformer().setParameter(OutputKeys.INDENT, "no");

            exportSystemView(absPath, th, skipBinary, noRecurse);
        } catch (TransformerException te) {
            throw new RepositoryException(te);
        } catch (SAXException se) {
            throw new RepositoryException(se);
        }
    }

    /**
     * @see javax.jcr.Session#exportDocumentView(String, org.xml.sax.ContentHandler, boolean, boolean)
     */
    public void exportDocumentView(String absPath, ContentHandler contentHandler, boolean skipBinary, boolean noRecurse) throws InvalidSerializedDataException, PathNotFoundException, SAXException, RepositoryException {
        checkIsAlive();
        Item item = getItem(absPath);
        if (!item.isNode()) {
            // a property instead of a node exists at the specified path
            throw new PathNotFoundException(absPath);
        }
        new DocViewSAXEventGenerator((Node) item, noRecurse, skipBinary, contentHandler).serialize();
    }

    /**
     * @see javax.jcr.Session#exportDocumentView(String, OutputStream, boolean, boolean)
     */
    public void exportDocumentView(String absPath, OutputStream out, boolean skipBinary, boolean noRecurse) throws InvalidSerializedDataException, IOException, PathNotFoundException, RepositoryException {
        SAXTransformerFactory stf = (SAXTransformerFactory) SAXTransformerFactory.newInstance();
        try {
            TransformerHandler th = stf.newTransformerHandler();
            th.setResult(new StreamResult(out));
            th.getTransformer().setParameter(OutputKeys.METHOD, "xml");
            th.getTransformer().setParameter(OutputKeys.ENCODING, "UTF-8");
            th.getTransformer().setParameter(OutputKeys.INDENT, "no");

            exportDocumentView(absPath, th, skipBinary, noRecurse);
        } catch (TransformerException te) {
            throw new RepositoryException(te);
        } catch (SAXException se) {
            throw new RepositoryException(se);
        }
    }

    /**
     * @see javax.jcr.Session#setNamespacePrefix(String, String)
     * @see LocalNamespaceMappings#setNamespacePrefix(String, String)
     */
    public void setNamespacePrefix(String prefix, String uri) throws NamespaceException, RepositoryException {
        nsMappings.setNamespacePrefix(prefix, uri);
    }

    /**
     * @see javax.jcr.Session#getNamespacePrefixes()
     * @see LocalNamespaceMappings#getPrefixes()
     */
    public String[] getNamespacePrefixes() throws RepositoryException {
        return nsMappings.getPrefixes();
    }

    /**
     * @see javax.jcr.Session#getNamespaceURI(String)
     * @see NamespaceResolver#getURI(String)
     */
    public String getNamespaceURI(String prefix) throws NamespaceException, RepositoryException {
        return nsMappings.getURI(prefix);
    }

    /**
     * @see javax.jcr.Session#getNamespacePrefix(String)
     * @see NamespaceResolver#getPrefix(String)
     */
    public String getNamespacePrefix(String uri) throws NamespaceException, RepositoryException {
        return nsMappings.getPrefix(uri);
    }

    /**
     * @see javax.jcr.Session#logout()
     */
    public void logout() {
        if (!alive) {
            // ignore
            return;
        }

        // notify listeners that session is about to be closed
        notifyLoggingOut();

        // dispose name nsResolver
        nsMappings.dispose();
        // dispose session item state manager
        itemStateManager.dispose();
        // dispose item manager
        itemManager.dispose();
        // dispose workspace
        workspace.dispose();

        // invalidate session
        alive = false;
        // finally notify listeners that session has been closed
        notifyLoggedOut();
    }

    /**
     * @see javax.jcr.Session#isLive()
     */
    public boolean isLive() {
        return alive;
    }

    /**
     * @see javax.jcr.Session#addLockToken(String)
     */
    public void addLockToken(String lt) {
        try {
            getLockManager().addLockToken(lt);
        } catch (RepositoryException e) {
            log.warn("Unable to add lock token '" +lt+ "' to this session.", e);
        }
    }

    /**
     * @see javax.jcr.Session#getLockTokens()
     */
    public String[] getLockTokens() {
        return getLockManager().getLockTokens();
    }

    /**
     * @see javax.jcr.Session#removeLockToken(String)
     */
    public void removeLockToken(String lt) {
        try {
            getLockManager().removeLockToken(lt);
        } catch (RepositoryException e) {
            log.warn("Unable to remove lock token '" +lt+ "' from this session.", e);
        }
    }

    //--------------------------------------< register and inform listeners >---
    /**
     * Add a <code>SessionListener</code>
     *
     * @param listener the new listener to be informed on modifications
     */
    public void addListener(SessionListener listener) {
        if (!listeners.containsKey(listener)) {
            listeners.put(listener, listener);
        }
    }

    /**
     * Remove a <code>SessionListener</code>
     *
     * @param listener an existing listener
     */
    public void removeListener(SessionListener listener) {
        listeners.remove(listener);
    }

    /**
     * Notify the listeners that this session is about to be closed.
     */
    private void notifyLoggingOut() {
        // copy listeners to array to avoid ConcurrentModificationException
        SessionListener[] la = (SessionListener[])listeners.values().toArray(new SessionListener[listeners.size()]);
        for (int i = 0; i < la.length; i++) {
            if (la[i] != null) {
                la[i].loggingOut(this);
            }
        }
    }

    /**
     * Notify the listeners that this session has been closed.
     */
    private void notifyLoggedOut() {
        // copy listeners to array to avoid ConcurrentModificationException
        SessionListener[] la = (SessionListener[])listeners.values().toArray(new SessionListener[listeners.size()]);
        for (int i = 0; i < la.length; i++) {
            if (la[i] != null) {
                la[i].loggedOut(this);
            }
        }
    }

    //-------------------------------------------------------< init methods >---
    protected WorkspaceImpl createWorkspaceInstance(RepositoryService service, SessionInfo sessionInfo) throws RepositoryException {
        return new WorkspaceImpl(sessionInfo.getWorkspaceName(), this, service, sessionInfo);
    }

    protected SessionItemStateManager createSessionItemStateManager(UpdatableItemStateManager workspaceStateManager, NamespaceResolver nsResolver) {
        return new SessionItemStateManager(workspaceStateManager, getIdFactory(), getValidator(), nsResolver);
    }

    protected ItemManager createItemManager(HierarchyManager hierarchyMgr) {
        return new ItemManagerImpl(hierarchyMgr, this);
    }

    //---------------------------------------------------< ManagerProvider > ---
    public NamespaceResolver getNamespaceResolver() {
        return nsMappings;
    }

    public HierarchyManager getHierarchyManager() {
        return itemStateManager.getHierarchyManager();
    }

    public ItemStateManager getItemStateManager() {
        return itemStateManager;
    }

    public LockManager getLockManager() {
        return workspace.getLockManager();
    }

    /**
     * Returns the <code>AccessManager</code> associated with this session.
     *
     * @return the <code>AccessManager</code> associated with this session
     */
    public AccessManager getAccessManager() {
        return workspace.getAccessManager();
    }

    /**
     * Returns the <code>VersionManager</code> associated with this session.
     *
     * @return the <code>VersionManager</code> associated with this session
     */
    public VersionManager getVersionManager() {
        return workspace.getVersionManager();
    }

    //--------------------------------------------------------------------------
    ItemManager getItemManager() {
        return itemManager;
    }

    // TODO public for SessionImport only. review
    public ItemStateValidator getValidator() {
        return validator;
    }

    // TODO public for SessionImport only. review
    public IdFactory getIdFactory() {
        return workspace.getIdFactory();
    }

    /**
     * Returns the <code>ItemStateManager</code> associated with this session.
     *
     * @return the <code>ItemStateManager</code> associated with this session
     */
    SessionItemStateManager getSessionItemStateManager() {
        return itemStateManager;
    }

    NodeTypeManagerImpl getNodeTypeManager() {
        return ntManager;
    }

    //--------------------------------------------------------------------------
    SessionImpl switchWorkspace(String workspaceName) throws AccessDeniedException,
        NoSuchWorkspaceException, RepositoryException {
        checkAccessibleWorkspace(workspaceName);
        
        SessionInfo info = config.getRepositoryService().obtain(sessionInfo, workspaceName);
        if (info instanceof XASessionInfo) {
            return new XASessionImpl((XASessionInfo) info, repository, config);
        } else {
            return new SessionImpl(info, repository, config);
        }
    }

    /**
     * Builds an qualified path from the given absolute path.
     *
     * @param absPath
     * @return
     * @throws RepositoryException if the resulting qualified path is not absolute
     * or if the given path cannot be resolved to a qualified path.
     */
    Path getQPath(String absPath) throws RepositoryException {
        try {
            Path p = PathFormat.parse(absPath, getNamespaceResolver());
            if (!p.isAbsolute()) {
                throw new RepositoryException("Not an absolute path: " + absPath);
            }
            return p;
        } catch (MalformedPathException mpe) {
            String msg = "Invalid path: " + absPath;
            log.debug(msg);
            throw new RepositoryException(msg, mpe);
        }
    }

    //------------------------------------------------------< check methods >---
    /**
     * Performs a sanity check on this session.
     *
     * @throws RepositoryException if this session has been rendered invalid
     * for some reason (e.g. if this session has been closed explicitly by logout)
     */
    void checkIsAlive() throws RepositoryException {
        // check session status
        if (!alive) {
            throw new RepositoryException("This session has been closed.");
        }
    }

    /**
     * Returns true if the repository supports the given option. False otherwise.
     *
     * @param option Any of the option constants defined by {@link Repository}
     * that either returns 'true' or 'false'. I.e.
     * <ul>
     * <li>{@link Repository#LEVEL_1_SUPPORTED}</li>
     * <li>{@link Repository#LEVEL_2_SUPPORTED}</li>
     * <li>{@link Repository#OPTION_TRANSACTIONS_SUPPORTED}</li>
     * <li>{@link Repository#OPTION_VERSIONING_SUPPORTED}</li>
     * <li>{@link Repository#OPTION_OBSERVATION_SUPPORTED}</li>
     * <li>{@link Repository#OPTION_LOCKING_SUPPORTED}</li>
     * <li>{@link Repository#OPTION_QUERY_SQL_SUPPORTED}</li>
     * </ul>
     * @return true if the repository supports the given option. False otherwise.
     */
    boolean isSupportedOption(String option) {
        String desc = repository.getDescriptor(option);
        return Boolean.valueOf(desc).booleanValue();
    }

    /**
     * Make sure the repository supports the option indicated by the given string.
     *
     * @param option Any of the option constants defined by {@link Repository}
     * that either returns 'true' or 'false'. I.e.
     * <ul>
     * <li>{@link Repository#LEVEL_1_SUPPORTED}</li>
     * <li>{@link Repository#LEVEL_2_SUPPORTED}</li>
     * <li>{@link Repository#OPTION_TRANSACTIONS_SUPPORTED}</li>
     * <li>{@link Repository#OPTION_VERSIONING_SUPPORTED}</li>
     * <li>{@link Repository#OPTION_OBSERVATION_SUPPORTED}</li>
     * <li>{@link Repository#OPTION_LOCKING_SUPPORTED}</li>
     * <li>{@link Repository#OPTION_QUERY_SQL_SUPPORTED}</li>
     * </ul>
     * @throws UnsupportedRepositoryOperationException
     * @throws RepositoryException
     * @see javax.jcr.Repository#getDescriptorKeys()
     */
    void checkSupportedOption(String option) throws UnsupportedRepositoryOperationException, RepositoryException {
        if (!isSupportedOption(option)) {
            throw new UnsupportedRepositoryOperationException(option + " is not supported by this repository.");
        }
    }

    /**
     * Checks if this nodes session has pending changes.
     *
     * @throws InvalidItemStateException if this nodes session has pending changes
     * @throws RepositoryException
     */
    void checkHasPendingChanges() throws RepositoryException {
        // check for pending changes
        if (hasPendingChanges()) {
            String msg = "Unable to perform operation. Session has pending changes.";
            log.debug(msg);
            throw new InvalidItemStateException(msg);
        }
    }

    /**
     * Check if the the workspace with the given name exists and is accessible
     * for this <code>Session</code>.
     *
     * @param workspaceName
     * @throws NoSuchWorkspaceException
     * @throws RepositoryException
     */
    void checkAccessibleWorkspace(String workspaceName) throws NoSuchWorkspaceException, RepositoryException {
        String[] wsps = workspace.getAccessibleWorkspaceNames();
        boolean accessible = false;
        for (int i = 0; i < wsps.length && !accessible; i++) {
            accessible = wsps[i].equals(workspaceName);
        }

        if (!accessible) {
            throw new NoSuchWorkspaceException("Unknown workspace: '" + workspaceName + "'.");
        }
    }
}
