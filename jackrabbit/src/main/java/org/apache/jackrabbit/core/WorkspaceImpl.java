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
package org.apache.jackrabbit.core;

import org.apache.jackrabbit.api.JackrabbitWorkspace;
import org.apache.jackrabbit.core.config.WorkspaceConfig;
import org.apache.jackrabbit.core.lock.LockManager;
import org.apache.jackrabbit.core.observation.EventStateCollection;
import org.apache.jackrabbit.core.observation.EventStateCollectionFactory;
import org.apache.jackrabbit.core.observation.ObservationDispatcher;
import org.apache.jackrabbit.core.observation.ObservationManagerImpl;
import org.apache.jackrabbit.core.query.QueryManagerImpl;
import org.apache.jackrabbit.core.state.LocalItemStateManager;
import org.apache.jackrabbit.core.state.SharedItemStateManager;
import org.apache.jackrabbit.core.version.VersionImpl;
import org.apache.jackrabbit.core.version.DateVersionSelector;
import org.apache.jackrabbit.core.version.VersionSelector;
import org.apache.jackrabbit.core.xml.ImportHandler;
import org.apache.jackrabbit.core.xml.Importer;
import org.apache.jackrabbit.core.xml.WorkspaceImporter;
import org.apache.jackrabbit.name.MalformedPathException;
import org.apache.jackrabbit.name.Path;
import org.apache.jackrabbit.name.PathFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.ContentHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import javax.jcr.AccessDeniedException;
import javax.jcr.InvalidItemStateException;
import javax.jcr.InvalidSerializedDataException;
import javax.jcr.ItemExistsException;
import javax.jcr.ItemNotFoundException;
import javax.jcr.NamespaceRegistry;
import javax.jcr.NoSuchWorkspaceException;
import javax.jcr.PathNotFoundException;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.UnsupportedRepositoryOperationException;
import javax.jcr.lock.LockException;
import javax.jcr.nodetype.ConstraintViolationException;
import javax.jcr.nodetype.NodeTypeManager;
import javax.jcr.observation.ObservationManager;
import javax.jcr.query.QueryManager;
import javax.jcr.version.Version;
import javax.jcr.version.VersionException;
import javax.jcr.version.VersionHistory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Iterator;

/**
 * A <code>WorkspaceImpl</code> ...
 */
public class WorkspaceImpl implements JackrabbitWorkspace, EventStateCollectionFactory {

    private static Logger log = LoggerFactory.getLogger(WorkspaceImpl.class);

    /**
     * The configuration of this <code>Workspace</code>
     */
    protected final WorkspaceConfig wspConfig;

    /**
     * The repository that created this workspace instance
     */
    protected final RepositoryImpl rep;

    /**
     * The persistent state mgr associated with the workspace represented by <i>this</i>
     * <code>Workspace</code> instance.
     */
    protected final LocalItemStateManager stateMgr;

    /**
     * The hierarchy mgr that reflects persistent state only
     * (i.e. that is isolated from transient changes made through
     * the session).
     */
    protected final CachingHierarchyManager hierMgr;

    /**
     * The <code>ObservationManager</code> instance for this session.
     */
    protected ObservationManagerImpl obsMgr;

    /**
     * The <code>QueryManager</code> for this <code>Workspace</code>.
     */
    protected QueryManagerImpl queryManager;

    /**
     * the session that was used to acquire this <code>Workspace</code>
     */
    protected final SessionImpl session;

    /**
     * The <code>LockManager</code> for this <code>Workspace</code>
     */
    protected LockManager lockMgr;

    /**
     * Protected constructor.
     *
     * @param wspConfig The workspace configuration
     * @param stateMgr  The shared item state manager
     * @param rep       The repository
     * @param session   The session
     */
    protected WorkspaceImpl(WorkspaceConfig wspConfig,
                            SharedItemStateManager stateMgr, RepositoryImpl rep,
                            SessionImpl session) {
        this.wspConfig = wspConfig;
        this.rep = rep;
        this.stateMgr = createItemStateManager(stateMgr);
        this.hierMgr = new CachingHierarchyManager(rep.getRootNodeId(),
                this.stateMgr, session.getNamespaceResolver());
        this.stateMgr.addListener(hierMgr);
        this.session = session;
    }

    /**
     * The hierarchy manager that reflects workspace state only
     * (i.e. that is isolated from transient changes made through
     * the session)
     *
     * @return the hierarchy manager of this workspace
     */
    public HierarchyManager getHierarchyManager() {
        return hierMgr;
    }

    /**
     * Returns the item state manager associated with the workspace
     * represented by <i>this</i> <code>WorkspaceImpl</code> instance.
     *
     * @return the item state manager of this workspace
     */
    public LocalItemStateManager getItemStateManager() {
        return stateMgr;
    }

    /**
     * Disposes this <code>WorkspaceImpl</code> and frees resources.
     */
    void dispose() {
        if (obsMgr != null) {
            obsMgr.dispose();
            obsMgr = null;
        }
    }

    /**
     * Performs a sanity check on this workspace and the associated session.
     *
     * @throws RepositoryException if this workspace has been rendered invalid
     *                             for some reason
     */
    public void sanityCheck() throws RepositoryException {
        // check session status
        session.sanityCheck();
    }

    //--------------------------------------------------< JackrabbitWorkspace >

    /**
     * Creates a workspace with the given name.
     *
     * @param workspaceName name of the new workspace
     * @throws AccessDeniedException if the current session is not allowed to
     *                               create the workspace
     * @throws RepositoryException   if a workspace with the given name
     *                               already exists or if another error occurs
     * @see #getAccessibleWorkspaceNames()
     */
    public void createWorkspace(String workspaceName)
            throws AccessDeniedException, RepositoryException {
        // check state of this instance
        sanityCheck();

        session.createWorkspace(workspaceName);
    }

    /**
     * Creates a workspace with the given name and a workspace configuration
     * template.
     *
     * @param workspaceName name of the new workspace
     * @param configTemplate the configuration template of the new workspace
     * @throws AccessDeniedException if the current session is not allowed to
     *                               create the workspace
     * @throws RepositoryException   if a workspace with the given name
     *                               already exists or if another error occurs
     * @see #getAccessibleWorkspaceNames()
     */
    public void createWorkspace(String workspaceName, InputSource configTemplate)
            throws AccessDeniedException, RepositoryException {
        // check state of this instance
        sanityCheck();

        session.createWorkspace(workspaceName, configTemplate);
    }

    /**
     * Returns the configuration of this workspace.
     * @return the workspace configuration
     */
    public WorkspaceConfig getConfig() {
        return wspConfig;
    }

    /**
     * @param srcAbsPath
     * @param srcWsp
     * @param destAbsPath
     * @param flag        one of
     *                    <ul>
     *                    <li><code>COPY</code></li>
     *                    <li><code>CLONE</code></li>
     *                    <li><code>CLONE_REMOVE_EXISTING</code></li>
     *                    </ul>
     * @throws ConstraintViolationException
     * @throws AccessDeniedException
     * @throws VersionException
     * @throws PathNotFoundException
     * @throws ItemExistsException
     * @throws LockException
     * @throws RepositoryException
     */
    private void internalCopy(String srcAbsPath,
                              WorkspaceImpl srcWsp,
                              String destAbsPath,
                              int flag)
            throws ConstraintViolationException, AccessDeniedException,
            VersionException, PathNotFoundException, ItemExistsException,
            LockException, RepositoryException {

        Path srcPath;
        try {
            srcPath = PathFormat.parse(srcAbsPath, session.getNamespaceResolver()).getNormalizedPath();
        } catch (MalformedPathException mpe) {
            String msg = "invalid path: " + srcAbsPath;
            log.debug(msg);
            throw new RepositoryException(msg, mpe);
        }
        if (!srcPath.isAbsolute()) {
            throw new RepositoryException("not an absolute path: " + srcAbsPath);
        }

        Path destPath;
        try {
            destPath = PathFormat.parse(destAbsPath, session.getNamespaceResolver()).getNormalizedPath();
        } catch (MalformedPathException mpe) {
            String msg = "invalid path: " + destAbsPath;
            log.debug(msg);
            throw new RepositoryException(msg, mpe);
        }
        if (!destPath.isAbsolute()) {
            throw new RepositoryException("not an absolute path: " + destAbsPath);
        }

        BatchedItemOperations ops =
                new BatchedItemOperations(stateMgr, rep.getNodeTypeRegistry(),
                        session.getLockManager(), session, hierMgr,
                        session.getNamespaceResolver());

        try {
            ops.edit();
        } catch (IllegalStateException e) {
            String msg = "unable to start edit operation";
            log.debug(msg);
            throw new RepositoryException(msg, e);
        }

        boolean succeeded = false;

        try {
            ops.copy(srcPath, srcWsp.getItemStateManager(),
                    srcWsp.getHierarchyManager(),
                    ((SessionImpl) srcWsp.getSession()).getAccessManager(),
                    destPath, flag);
            ops.update();
            succeeded = true;
        } finally {
            if (!succeeded) {
                // update operation failed, cancel all modifications
                ops.cancel();
            }
        }
    }

    /**
     * Return the lock manager for this workspace. If not already done, creates
     * a new instance.
     *
     * @return lock manager for this workspace
     * @throws RepositoryException if an error occurs
     */
    public synchronized LockManager getLockManager() throws RepositoryException {

        // check state of this instance
        sanityCheck();

        if (lockMgr == null) {
            lockMgr = rep.getLockManager(wspConfig.getName());
        }
        return lockMgr;
    }

    //------------------------------------------------------------< Workspace >
    /**
     * {@inheritDoc}
     */
    public String getName() {
        return wspConfig.getName();
    }

    /**
     * {@inheritDoc}
     */
    public Session getSession() {
        return session;
    }

    /**
     * {@inheritDoc}
     */
    public NamespaceRegistry getNamespaceRegistry() throws RepositoryException {
        // check state of this instance
        sanityCheck();

        return rep.getNamespaceRegistry();
    }

    /**
     * {@inheritDoc}
     */
    public NodeTypeManager getNodeTypeManager() throws RepositoryException {
        // check state of this instance
        sanityCheck();

        return session.getNodeTypeManager();
    }

    /**
     * {@inheritDoc}
     */
    public void clone(String srcWorkspace, String srcAbsPath,
                      String destAbsPath, boolean removeExisting)
            throws NoSuchWorkspaceException, ConstraintViolationException,
            VersionException, AccessDeniedException, PathNotFoundException,
            ItemExistsException, LockException, RepositoryException {

        // check state of this instance
        sanityCheck();

        // check workspace name
        if (getName().equals(srcWorkspace)) {
            // same as current workspace
            String msg = srcWorkspace + ": illegal workspace (same as current)";
            log.debug(msg);
            throw new RepositoryException(msg);
        }

        // check authorization for specified workspace
        if (!session.getAccessManager().canAccess(srcWorkspace)) {
            throw new AccessDeniedException("not authorized to access " + srcWorkspace);
        }

        // clone (i.e. pull) subtree at srcAbsPath from srcWorkspace
        // to 'this' workspace at destAbsPath

        SessionImpl srcSession = null;
        try {
            // create session on other workspace for current subject
            // (may throw NoSuchWorkspaceException and AccessDeniedException)
            srcSession = rep.createSession(session.getSubject(), srcWorkspace);
            WorkspaceImpl srcWsp = (WorkspaceImpl) srcSession.getWorkspace();

            // do cross-workspace copy
            int mode = BatchedItemOperations.CLONE;
            if (removeExisting) {
                mode = BatchedItemOperations.CLONE_REMOVE_EXISTING;
            }
            internalCopy(srcAbsPath, srcWsp, destAbsPath, mode);
        } finally {
            if (srcSession != null) {
                // we don't need the other session anymore, logout
                srcSession.logout();
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    public void copy(String srcAbsPath, String destAbsPath)
            throws ConstraintViolationException, VersionException,
            AccessDeniedException, PathNotFoundException, ItemExistsException,
            LockException, RepositoryException {

        // check state of this instance
        sanityCheck();

        // do intra-workspace copy
        internalCopy(srcAbsPath, this, destAbsPath, BatchedItemOperations.COPY);
    }

    /**
     * {@inheritDoc}
     */
    public void copy(String srcWorkspace, String srcAbsPath, String destAbsPath)
            throws NoSuchWorkspaceException, ConstraintViolationException,
            VersionException, AccessDeniedException, PathNotFoundException,
            ItemExistsException, LockException, RepositoryException {

        // check state of this instance
        sanityCheck();

        // check workspace name
        if (getName().equals(srcWorkspace)) {
            // same as current workspace, delegate to intra-workspace copy method
            copy(srcAbsPath, destAbsPath);
            return;
        }

        // check authorization for specified workspace
        if (!session.getAccessManager().canAccess(srcWorkspace)) {
            throw new AccessDeniedException("not authorized to access " + srcWorkspace);
        }

        // copy (i.e. pull) subtree at srcAbsPath from srcWorkspace
        // to 'this' workspace at destAbsPath

        SessionImpl srcSession = null;
        try {
            // create session on other workspace for current subject
            // (may throw NoSuchWorkspaceException and AccessDeniedException)
            srcSession = rep.createSession(session.getSubject(), srcWorkspace);
            WorkspaceImpl srcWsp = (WorkspaceImpl) srcSession.getWorkspace();

            // do cross-workspace copy
            internalCopy(srcAbsPath, srcWsp, destAbsPath, BatchedItemOperations.COPY);
        } finally {
            if (srcSession != null) {
                // we don't need the other session anymore, logout
                srcSession.logout();
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    public void move(String srcAbsPath, String destAbsPath)
            throws ConstraintViolationException, VersionException,
            AccessDeniedException, PathNotFoundException, ItemExistsException,
            LockException, RepositoryException {

        // check state of this instance
        sanityCheck();

        // intra-workspace move...

        Path srcPath;
        try {
            srcPath = PathFormat.parse(srcAbsPath, session.getNamespaceResolver()).getNormalizedPath();
        } catch (MalformedPathException mpe) {
            String msg = "invalid path: " + srcAbsPath;
            log.debug(msg);
            throw new RepositoryException(msg, mpe);
        }
        if (!srcPath.isAbsolute()) {
            throw new RepositoryException("not an absolute path: " + srcAbsPath);
        }

        Path destPath;
        try {
            destPath = PathFormat.parse(destAbsPath, session.getNamespaceResolver()).getNormalizedPath();
        } catch (MalformedPathException mpe) {
            String msg = "invalid path: " + destAbsPath;
            log.debug(msg);
            throw new RepositoryException(msg, mpe);
        }
        if (!destPath.isAbsolute()) {
            throw new RepositoryException("not an absolute path: " + destAbsPath);
        }

        BatchedItemOperations ops =
                new BatchedItemOperations(stateMgr, rep.getNodeTypeRegistry(),
                        session.getLockManager(), session, hierMgr,
                        session.getNamespaceResolver());

        try {
            ops.edit();
        } catch (IllegalStateException e) {
            String msg = "unable to start edit operation";
            log.debug(msg);
            throw new RepositoryException(msg, e);
        }

        boolean succeeded = false;

        try {
            ops.move(srcPath, destPath);
            ops.update();
            succeeded = true;
        } finally {
            if (!succeeded) {
                // update operation failed, cancel all modifications
                ops.cancel();
            }
        }
    }

    /**
     * Returns the observation manager of this session. The observation manager
     * is lazily created if it does not exist yet.
     *
     * @return the observation manager of this session
     * @throws RepositoryException if a repository error occurs
     */
    public ObservationManager getObservationManager()
            throws RepositoryException {
        // check state of this instance
        sanityCheck();

        if (obsMgr == null) {
            try {
                obsMgr = new ObservationManagerImpl(
                        rep.getObservationDispatcher(wspConfig.getName()),
                        session, session.getItemManager());
            } catch (NoSuchWorkspaceException nswe) {
                // should never get here
                String msg = "internal error: failed to instantiate observation manager";
                log.debug(msg);
                throw new RepositoryException(msg, nswe);
            }
        }
        return obsMgr;
    }

    /**
     * {@inheritDoc}
     */
    public synchronized QueryManager getQueryManager() throws RepositoryException {

        // check state of this instance
        sanityCheck();

        if (queryManager == null) {
            SearchManager searchManager;
            try {
                searchManager = rep.getSearchManager(wspConfig.getName());
                if (searchManager == null) {
                    String msg = "no search manager configured for this workspace";
                    log.debug(msg);
                    throw new RepositoryException(msg);
                }
            } catch (NoSuchWorkspaceException nswe) {
                // should never get here
                String msg = "internal error: failed to instantiate query manager";
                log.debug(msg);
                throw new RepositoryException(msg, nswe);
            }
            queryManager = new QueryManagerImpl(session, session.getItemManager(), searchManager);
        }
        return queryManager;
    }

    /**
     * {@inheritDoc}
     */
    public void restore(Version[] versions, boolean removeExisting)
            throws ItemExistsException, UnsupportedRepositoryOperationException,
            VersionException, LockException, InvalidItemStateException,
            RepositoryException {

        // todo: perform restore operations direct on the node states

        // check state of this instance
        sanityCheck();

        // add all versions to map of versions to restore
        final HashMap toRestore = new HashMap();
        for (int i = 0; i < versions.length; i++) {
            VersionImpl v = (VersionImpl) versions[i];
            VersionHistory vh = v.getContainingHistory();
            // check for collision
            if (toRestore.containsKey(vh.getUUID())) {
                throw new VersionException("Unable to restore. Two or more versions have same version history.");
            }
            toRestore.put(vh.getUUID(), v);
        }

        // create a version selector to the set of versions
        VersionSelector vsel = new VersionSelector() {
            public Version select(VersionHistory versionHistory) throws RepositoryException {
                // try to select version as specified
                Version v = (Version) toRestore.get(versionHistory.getUUID());
                if (v == null) {
                    // select latest one
                    v = DateVersionSelector.selectByDate(versionHistory, null);
                }
                return v;
            }
        };

        // check for pending changes
        if (session.hasPendingChanges()) {
            String msg = "Unable to restore version. Session has pending changes.";
            log.debug(msg);
            throw new InvalidItemStateException(msg);
        }

        try {
            // now restore all versions that have a node in the ws
            int numRestored = 0;
            while (toRestore.size() > 0) {
                Version[] restored = null;
                Iterator iter = toRestore.values().iterator();
                while (iter.hasNext()) {
                    VersionImpl v = (VersionImpl) iter.next();
                    try {
                        NodeImpl node = (NodeImpl) session.getNodeByUUID(v.getFrozenNode().getFrozenUUID());
                        restored = node.internalRestore(v, vsel, removeExisting);
                        // remove restored versions from set
                        for (int i = 0; i < restored.length; i++) {
                            toRestore.remove(restored[i].getContainingHistory().getUUID());
                        }
                        numRestored += restored.length;
                        break;
                    } catch (ItemNotFoundException e) {
                        // ignore
                    }
                }
                if (restored == null) {
                    if (numRestored == 0) {
                        throw new VersionException("Unable to restore. At least one version needs"
                                + " existing versionable node in workspace.");
                    } else {
                        throw new VersionException("Unable to restore. All versions with non"
                                + " existing versionable nodes need parent.");
                    }
                }
            }
        } catch (RepositoryException e) {
            // revert session
            try {
                log.error("reverting changes applied during restore...");
                session.refresh(false);
            } catch (RepositoryException e1) {
                // ignore this
            }
            throw e;
        }
        session.save();
    }

    /**
     * {@inheritDoc}
     */
    public String[] getAccessibleWorkspaceNames() throws RepositoryException {
        // check state of this instance
        sanityCheck();

        return session.getWorkspaceNames();
    }

    /**
     * {@inheritDoc}
     */
    public ContentHandler getImportContentHandler(String parentAbsPath,
                                                  int uuidBehavior)
            throws PathNotFoundException, ConstraintViolationException,
            VersionException, LockException, RepositoryException {

        // check state of this instance
        sanityCheck();

        Path parentPath;
        try {
            parentPath = PathFormat.parse(parentAbsPath, session.getNamespaceResolver()).getNormalizedPath();
        } catch (MalformedPathException mpe) {
            String msg = "invalid path: " + parentAbsPath;
            log.debug(msg);
            throw new RepositoryException(msg, mpe);
        }
        if (!parentPath.isAbsolute()) {
            throw new RepositoryException("not an absolute path: " + parentAbsPath);
        }

        Importer importer = new WorkspaceImporter(parentPath, this,
                rep.getNodeTypeRegistry(), uuidBehavior);
        return new ImportHandler(importer, session.getNamespaceResolver(),
                rep.getNamespaceRegistry());
    }

    /**
     * {@inheritDoc}
     */
    public void importXML(String parentAbsPath, InputStream in,
                          int uuidBehavior)
            throws IOException, PathNotFoundException, ItemExistsException,
            ConstraintViolationException, InvalidSerializedDataException,
            LockException, RepositoryException {

        ImportHandler handler =
                (ImportHandler) getImportContentHandler(parentAbsPath, uuidBehavior);
        try {
            SAXParserFactory factory = SAXParserFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(
                    "http://xml.org/sax/features/namespace-prefixes", false);

            SAXParser parser = factory.newSAXParser();
            parser.parse(new InputSource(in), handler);
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
        } catch (ParserConfigurationException e) {
            throw new RepositoryException("SAX parser configuration error", e);
        }
    }

    /**
     * Create the persistent item state manager on top of the shared item
     * state manager. May be overridden by subclasses.
     * @param shared shared item state manager
     * @return local item state manager
     */
    protected LocalItemStateManager createItemStateManager(SharedItemStateManager shared) {
        return new LocalItemStateManager(shared, this);
    }

    //------------------------------------------< EventStateCollectionFactory >

    /**
     * {@inheritDoc}
     * <p/>
     * Implemented in this object and forwarded rather than {@link #obsMgr}
     * since creation of the latter is lazy.
     */
    public EventStateCollection createEventStateCollection()
            throws RepositoryException {

        return ((ObservationManagerImpl) getObservationManager()).createEventStateCollection();
    }
}

