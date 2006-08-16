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

import org.apache.jackrabbit.name.NamespaceResolver;
import org.apache.jackrabbit.jcr2spi.state.UpdatableItemStateManager;
import org.apache.jackrabbit.jcr2spi.state.ItemStateManager;
import org.apache.jackrabbit.jcr2spi.state.ItemState;
import org.apache.jackrabbit.jcr2spi.nodetype.NodeTypeRegistry;
import org.apache.jackrabbit.jcr2spi.query.QueryManagerImpl;
import org.apache.jackrabbit.jcr2spi.operation.Move;
import org.apache.jackrabbit.jcr2spi.operation.Copy;
import org.apache.jackrabbit.jcr2spi.operation.Clone;
import org.apache.jackrabbit.jcr2spi.operation.Operation;
import org.apache.jackrabbit.jcr2spi.security.AccessManager;
import org.apache.jackrabbit.jcr2spi.lock.LockManager;
import org.apache.jackrabbit.jcr2spi.lock.LockManagerImpl;
import org.apache.jackrabbit.jcr2spi.lock.DefaultLockManager;
import org.apache.jackrabbit.jcr2spi.version.VersionManager;
import org.apache.jackrabbit.jcr2spi.version.VersionManagerImpl;
import org.apache.jackrabbit.jcr2spi.version.DefaultVersionManager;
import org.apache.jackrabbit.jcr2spi.version.VersionImpl;
import org.apache.jackrabbit.jcr2spi.name.NamespaceRegistryImpl;
import org.apache.jackrabbit.jcr2spi.observation.ObservationManagerImpl;
import org.apache.jackrabbit.jcr2spi.xml.WorkspaceContentHandler;
import org.apache.jackrabbit.spi.IdFactory;
import org.apache.jackrabbit.spi.RepositoryService;
import org.apache.jackrabbit.spi.SessionInfo;
import org.apache.jackrabbit.name.Path;
import org.apache.jackrabbit.spi.NodeId;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;
import org.xml.sax.ContentHandler;

import javax.jcr.observation.ObservationManager;
import javax.jcr.query.QueryManager;
import javax.jcr.lock.LockException;
import javax.jcr.version.VersionException;
import javax.jcr.version.Version;
import javax.jcr.nodetype.ConstraintViolationException;
import javax.jcr.nodetype.NodeTypeManager;
import javax.jcr.Workspace;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.AccessDeniedException;
import javax.jcr.PathNotFoundException;
import javax.jcr.ItemExistsException;
import javax.jcr.Repository;
import javax.jcr.NoSuchWorkspaceException;
import javax.jcr.UnsupportedRepositoryOperationException;
import javax.jcr.InvalidItemStateException;
import javax.jcr.NamespaceRegistry;
import javax.jcr.InvalidSerializedDataException;
import java.io.InputStream;
import java.io.IOException;

/**
 * <code>WorkspaceImpl</code>...
 */
public class WorkspaceImpl implements Workspace, ManagerProvider {

    private static Logger log = LoggerFactory.getLogger(WorkspaceImpl.class);

    private final String name;
    private final SessionImpl session;

    private final WorkspaceManager wspManager;

    /**
     * The hierarchy manager that reflects workspace state only
     * (i.e. that is isolated from transient changes made through
     * the session)
     */
    private HierarchyManager hierManager;

    private QueryManager qManager;
    private ObservationManager obsManager;
    private LockManager lockManager;
    private VersionManager versionManager;

    public WorkspaceImpl(String name, SessionImpl session, RepositoryService service, SessionInfo sessionInfo) throws RepositoryException {
        this.name = name;
        this.session = session;

        wspManager = this.createManager(service, sessionInfo);
    }

    //----------------------------------------------------------< Workspace >---
    /**
     * @see javax.jcr.Workspace#getSession()
     */
    public Session getSession() {
        return session;
    }

    /**
     * @see javax.jcr.Workspace#getName()
     */
    public String getName() {
        return name;
    }

    /**
     * @see javax.jcr.Workspace#copy(String, String)
     */
    public void copy(String srcAbsPath, String destAbsPath) throws ConstraintViolationException, VersionException, AccessDeniedException, PathNotFoundException, ItemExistsException, LockException, RepositoryException {
        session.checkSupportedOption(Repository.LEVEL_2_SUPPORTED);
        session.checkIsAlive();

        // do intra-workspace copy
        Path srcPath = session.getQPath(srcAbsPath);
        Path destPath = session.getQPath(destAbsPath);

        Operation op = Copy.create(srcPath, destPath, this, this);
        getUpdatableItemStateManager().execute(op);
    }

    /**
     * @see javax.jcr.Workspace#copy(String, String, String)
     */
    public void copy(String srcWorkspace, String srcAbsPath, String destAbsPath) throws NoSuchWorkspaceException, ConstraintViolationException, VersionException, AccessDeniedException, PathNotFoundException, ItemExistsException, LockException, RepositoryException {
        session.checkSupportedOption(Repository.LEVEL_2_SUPPORTED);
        session.checkIsAlive();

        // check workspace name
        if (getName().equals(srcWorkspace)) {
            // same as current workspace, delegate to intra-workspace copy method
            copy(srcAbsPath, destAbsPath);
            return;
        }

        // make sure the specified workspace is visible for the current session.
        session.checkAccessibleWorkspace(srcWorkspace);

        Path srcPath = session.getQPath(srcAbsPath);
        Path destPath = session.getQPath(destAbsPath);

        // copy (i.e. pull) subtree at srcAbsPath from srcWorkspace
        // to 'this' workspace at destAbsPath

        SessionImpl srcSession = null;
        try {
            // create session on other workspace for current subject
            // (may throw NoSuchWorkspaceException and AccessDeniedException)
            // DIFF JR: srcSession = rep.createSession(session.getSubject(), srcWorkspace);
            srcSession = session.switchWorkspace(srcWorkspace);
            WorkspaceImpl srcWsp = (WorkspaceImpl) srcSession.getWorkspace();

            // do cross-workspace copy
            Operation op = Copy.create(srcPath, destPath, srcWsp.getName(), srcWsp, this);
            getUpdatableItemStateManager().execute(op);
        } finally {
            if (srcSession != null) {
                // we don't need the other session anymore, logout
                srcSession.logout();
            }
        }
    }

    /**
     * @see javax.jcr.Workspace#clone(String, String, String, boolean)
     */
    public void clone(String srcWorkspace, String srcAbsPath, String destAbsPath, boolean removeExisting) throws NoSuchWorkspaceException, ConstraintViolationException, VersionException, AccessDeniedException, PathNotFoundException, ItemExistsException, LockException, RepositoryException {
        session.checkSupportedOption(Repository.LEVEL_2_SUPPORTED);
        session.checkIsAlive();

        // check workspace name
        if (getName().equals(srcWorkspace)) {
            // same as current workspace
            String msg = srcWorkspace + ": illegal workspace (same as current)";
            log.debug(msg);
            throw new RepositoryException(msg);
        }

        // make sure the specified workspace is visible for the current session.
        session.checkAccessibleWorkspace(srcWorkspace);

        Path srcPath = session.getQPath(srcAbsPath);
        Path destPath = session.getQPath(destAbsPath);

        // clone (i.e. pull) subtree at srcAbsPath from srcWorkspace
        // to 'this' workspace at destAbsPath

        SessionImpl srcSession = null;
        try {
            // create session on other workspace for current subject
            // (may throw NoSuchWorkspaceException and AccessDeniedException)
            // DIFF JR: srcSession = rep.createSession(session.getSubject(), srcWorkspace);
            srcSession = session.switchWorkspace(srcWorkspace);
            WorkspaceImpl srcWsp = (WorkspaceImpl) srcSession.getWorkspace();

            // do clone
            Operation op = Clone.create(srcPath, destPath, srcWsp.getName(), removeExisting, srcWsp, this);
            getUpdatableItemStateManager().execute(op);
        } finally {
            if (srcSession != null) {
                // we don't need the other session anymore, logout
                srcSession.logout();
            }
        }
    }

    /**
     * @see javax.jcr.Workspace#move(String, String)
     */
    public void move(String srcAbsPath, String destAbsPath) throws ConstraintViolationException, VersionException, AccessDeniedException, PathNotFoundException, ItemExistsException, LockException, RepositoryException {
        session.checkSupportedOption(Repository.LEVEL_2_SUPPORTED);
        session.checkIsAlive();

        Path srcPath = session.getQPath(srcAbsPath);
        Path destPath = session.getQPath(destAbsPath);

        Operation op = Move.create(srcPath, destPath, getHierarchyManager(), getNamespaceResolver());
        getUpdatableItemStateManager().execute(op);
    }

    /**
     * @see javax.jcr.Workspace#restore(Version[], boolean)
     */
    public void restore(Version[] versions, boolean removeExisting) throws ItemExistsException, UnsupportedRepositoryOperationException, VersionException, LockException, InvalidItemStateException, RepositoryException {
        session.checkSupportedOption(Repository.OPTION_VERSIONING_SUPPORTED);
        session.checkHasPendingChanges();

        NodeId[] versionIds = new NodeId[versions.length];
        for (int i = 0; i < versions.length; i++) {
            if (versions[i] instanceof VersionImpl) {
                ItemState vState = ((NodeImpl)versions[i]).getItemState();
                versionIds[i] = (NodeId) vState.getId();
            } else {
                throw new RepositoryException("Unexpected error: Failed to retrieve a valid ID for the given version " + versions[i].getPath());
            }
        }
        getVersionManager().restore(versionIds, removeExisting);
    }

    /**
     * @see javax.jcr.Workspace#getQueryManager()
     */
    public QueryManager getQueryManager() throws RepositoryException {
        session.checkIsAlive();
        if (qManager == null) {
            qManager = new QueryManagerImpl(session, session.getNamespaceResolver(),
                session.getItemManager(), session.getItemStateManager(), wspManager);
        }
        return qManager;
    }

    /**
     * @see javax.jcr.Workspace#getNamespaceRegistry()
     */
    public NamespaceRegistry getNamespaceRegistry() throws RepositoryException {
        session.checkIsAlive();
        return wspManager.getNamespaceRegistryImpl();
    }

    /**
     * @see javax.jcr.Workspace#getNodeTypeManager()
     */
    public NodeTypeManager getNodeTypeManager() throws RepositoryException {
        session.checkIsAlive();
        return session.getNodeTypeManager();
    }

    /**
     * @see javax.jcr.Workspace#getObservationManager()
     */
    public ObservationManager getObservationManager() throws UnsupportedRepositoryOperationException, RepositoryException {
        session.checkSupportedOption(Repository.OPTION_OBSERVATION_SUPPORTED);
        session.checkIsAlive();
        if (obsManager == null) {
            obsManager = createObservationManager(getNamespaceResolver(), getNodeTypeRegistry());
        }
        return obsManager;
    }

    /**
     * @see javax.jcr.Workspace#getAccessibleWorkspaceNames()
     */
    public String[] getAccessibleWorkspaceNames() throws RepositoryException {
        session.checkIsAlive();
        return wspManager.getWorkspaceNames();
    }

    /**
     * @see javax.jcr.Workspace#getImportContentHandler(String, int)
     */
    public ContentHandler getImportContentHandler(String parentAbsPath, int uuidBehavior)
        throws PathNotFoundException, ConstraintViolationException, VersionException,
        LockException, RepositoryException {
        session.checkSupportedOption(Repository.LEVEL_2_SUPPORTED);
        session.checkIsAlive();

        Path parentPath = session.getQPath(parentAbsPath);
        ItemState parentState = getHierarchyManager().getItemState(parentPath);
        if (parentState.isNode()) {
            return new WorkspaceContentHandler(this, parentAbsPath, uuidBehavior);
        } else {
            throw new PathNotFoundException("No node at path " + parentAbsPath);
        }
    }

    /**
     * @see javax.jcr.Workspace#importXML(String, InputStream, int)
     */
    public void importXML(String parentAbsPath, InputStream in,
                          int uuidBehavior)
        throws IOException, PathNotFoundException, ItemExistsException,
        ConstraintViolationException, InvalidSerializedDataException,
        LockException, RepositoryException {

        Path parentPath = session.getQPath(parentAbsPath);
        ItemState parentState = getHierarchyManager().getItemState(parentPath);
        if (parentState.isNode()) {
            wspManager.importXml((NodeId) parentState.getId(), in, uuidBehavior);
        } else {
            throw new PathNotFoundException("No node at path " + parentAbsPath);
        }
    }

    //----------------------------------------------------< ManagerProvider >---
    /**
     * @see ManagerProvider#getNamespaceResolver()
     */
    public NamespaceResolver getNamespaceResolver() {
        return session.getNamespaceResolver();
    }

    /**
     * @see ManagerProvider#getHierarchyManager()
     */
    public HierarchyManager getHierarchyManager() {
        if (hierManager == null) {
            hierManager = new HierarchyManagerImpl(getItemStateManager(), getNamespaceResolver());
        }
        return hierManager;
    }

    /**
     * @see ManagerProvider#getItemStateManager()
     */
    public ItemStateManager getItemStateManager() {
        return wspManager;
    }

    /**
     * @see ManagerProvider#getAccessManager()
     */
    public AccessManager getAccessManager() {
        return wspManager;
    }

    /**
     * @see ManagerProvider#getLockManager()
     */
    public LockManager getLockManager() {
        if (lockManager == null) {
            lockManager = createLockManager(wspManager, session.getItemManager());
        }
        return lockManager;
    }

    /**
     * @see ManagerProvider#getVersionManager()
     */
    public VersionManager getVersionManager() {
        if (versionManager == null) {
            versionManager = createVersionManager(wspManager);
        }
        return versionManager;
    }

    //------------------------------------< implementation specific methods >---
    IdFactory getIdFactory() {
        return wspManager.getIdFactory();
    }

    NodeTypeRegistry getNodeTypeRegistry() {
        return wspManager.getNodeTypeRegistry();
    }

    NamespaceRegistryImpl getNamespaceRegistryImpl() {
        return wspManager.getNamespaceRegistryImpl();
    }

    /**
     * Returns the state manager associated with the workspace
     * represented by <i>this</i> <code>WorkspaceImpl</code> instance.
     *
     * @return the state manager of this workspace
     */
    UpdatableItemStateManager getUpdatableItemStateManager() {
        return wspManager;
    }
    //-----------------------------------------------------< initialization >---
    /**
     * Create the workspace state manager. May be overridden by subclasses.
     *
     * @param service the RepositoryService
     * @return state manager
     */
    protected WorkspaceManager createManager(RepositoryService service, SessionInfo sessionInfo) throws RepositoryException {
        return new WorkspaceManager(service, sessionInfo);
    }

    /**
     *
     * @param wspManager
     * @param itemManager
     * @return
     */
    protected LockManager createLockManager(WorkspaceManager wspManager, ItemManager itemManager) {
        if (session.isSupportedOption(Repository.OPTION_LOCKING_SUPPORTED)) {
            LockManager lMgr = new LockManagerImpl(wspManager, itemManager);
            session.addListener((LockManagerImpl) lMgr);
            return lMgr;
        } else {
            return new DefaultLockManager();
        }
    }

    /**
     *
     * @param stateMgr
     * @return
     */
    protected VersionManager createVersionManager(UpdatableItemStateManager stateMgr) {
        if (session.isSupportedOption(Repository.OPTION_VERSIONING_SUPPORTED)) {
            return new VersionManagerImpl(stateMgr);
        } else {
            return new DefaultVersionManager();
        }
    }

    /**
     *
     * @return
     */
    protected ObservationManager createObservationManager(NamespaceResolver nsResolver, NodeTypeRegistry ntRegistry) {
        ObservationManagerImpl obsMgr = new ObservationManagerImpl(nsResolver, ntRegistry);
        wspManager.addEventListener(obsMgr);
        return obsMgr;
    }
}
