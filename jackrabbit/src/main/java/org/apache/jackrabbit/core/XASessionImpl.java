/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  The ASF licenses this file to You
 * under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License.
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

import org.apache.jackrabbit.core.config.WorkspaceConfig;
import org.apache.jackrabbit.core.security.AuthContext;
import org.apache.jackrabbit.core.lock.LockManager;
import org.apache.jackrabbit.core.lock.XALockManager;
import org.apache.jackrabbit.core.lock.LockManagerImpl;
import org.apache.jackrabbit.core.state.XAItemStateManager;
import org.apache.jackrabbit.core.state.SharedItemStateManager;
import org.apache.jackrabbit.core.state.SessionItemStateManager;
import org.apache.jackrabbit.core.version.VersionManager;
import org.apache.jackrabbit.core.version.VersionManagerImpl;
import org.apache.jackrabbit.core.version.XAVersionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.AccessDeniedException;
import javax.jcr.RepositoryException;
import javax.security.auth.Subject;
import javax.transaction.xa.XAException;
import javax.transaction.xa.XAResource;
import javax.transaction.xa.Xid;
import java.util.HashMap;
import java.util.Map;

/**
 * Session extension that provides XA support.
 */
public class XASessionImpl extends SessionImpl
        implements XASession, XAResource {

    /**
     * Logger instance
     */
    private static final Logger log = LoggerFactory.getLogger(XASessionImpl.class);

    /**
     * Global transactions
     */
    private static final Map txGlobal = new HashMap();

    /**
     * Default transaction timeout, in seconds.
     */
    private static final int DEFAULT_TX_TIMEOUT = 5;

    /**
     * Currently associated transaction
     */
    private TransactionContext tx;

    /**
     * Transaction timeout, in seconds
     */
    private int txTimeout;

    /**
     * List of transactional resources.
     */
    private InternalXAResource[] txResources;

    /**
     * Session-local lock manager.
     */
    private LockManager lockMgr;

    /**
     * Create a new instance of this class.
     *
     * @param rep          repository
     * @param loginContext login context containing authenticated subject
     * @param wspConfig    workspace configuration
     * @throws AccessDeniedException if the subject of the given login context
     *                               is not granted access to the specified
     *                               workspace
     * @throws RepositoryException   if another error occurs
     */
    protected XASessionImpl(RepositoryImpl rep, AuthContext loginContext,
                            WorkspaceConfig wspConfig)
            throws AccessDeniedException, RepositoryException {

        super(rep, loginContext, wspConfig);

        init();
    }

    /**
     * Create a new instance of this class.
     *
     * @param rep       repository
     * @param subject   authenticated subject
     * @param wspConfig workspace configuration
     * @throws AccessDeniedException if the given subject is not granted access
     *                               to the specified workspace
     * @throws RepositoryException   if another error occurs
     */
    protected XASessionImpl(RepositoryImpl rep, Subject subject,
                            WorkspaceConfig wspConfig)
            throws AccessDeniedException, RepositoryException {

        super(rep, subject, wspConfig);

        init();
    }

    /**
     * Initialize this object.
     */
    private void init() throws RepositoryException {
        XAItemStateManager stateMgr = (XAItemStateManager) wsp.getItemStateManager();
        XALockManager lockMgr = (XALockManager) getLockManager();
        XAVersionManager versionMgr = (XAVersionManager) getVersionManager();

        txResources = new InternalXAResource[] {
            stateMgr, lockMgr, versionMgr
        };
        stateMgr.setVirtualProvider(versionMgr);
    }

    /**
     * {@inheritDoc}
     */
    protected WorkspaceImpl createWorkspaceInstance(WorkspaceConfig wspConfig,
                                                    SharedItemStateManager stateMgr,
                                                    RepositoryImpl rep,
                                                    SessionImpl session) {
        return new XAWorkspace(wspConfig, stateMgr, rep, session);
    }

    /**
     * {@inheritDoc}
     */
    protected VersionManager createVersionManager(RepositoryImpl rep)
            throws RepositoryException {

        VersionManagerImpl vMgr = (VersionManagerImpl) rep.getVersionManager();
        return new XAVersionManager(vMgr, rep.getNodeTypeRegistry(), this);
    }

    /**
     * {@inheritDoc}
     */
    protected ItemManager createItemManager(SessionItemStateManager itemStateMgr,
                                            HierarchyManager hierMgr) {
        return new XAItemManager(itemStateMgr, hierMgr, this,
                ntMgr.getRootNodeDefinition(), rep.getRootNodeId());
    }

    /**
     * {@inheritDoc}
     */
    public LockManager getLockManager() throws RepositoryException {
        if (lockMgr == null) {
            LockManagerImpl lockMgr = (LockManagerImpl) wsp.getLockManager();
            this.lockMgr = new XALockManager(this, lockMgr);
        }
        return lockMgr;
    }
    //-------------------------------------------------------------< XASession >
    /**
     * {@inheritDoc}
     */
    public XAResource getXAResource() {
        return this;
    }

    //------------------------------------------------------------< XAResource >
    /**
     * {@inheritDoc}
     */
    public int getTransactionTimeout() {
        return txTimeout == 0 ? DEFAULT_TX_TIMEOUT : txTimeout;
    }

    /**
     * {@inheritDoc}
     */
    public boolean setTransactionTimeout(int seconds) {
        txTimeout = seconds;
        return true;
    }

    /**
     * {@inheritDoc}
     * <p/>
     * Two resources belong to the same resource manager if both connections
     * (i.e. sessions) have the same credentials.
     */
    public boolean isSameRM(XAResource xares) throws XAException {
        if (xares instanceof XASessionImpl) {
            XASessionImpl xases = (XASessionImpl) xares;
            return stringsEqual(userId, xases.userId);
        }
        return false;
    }

    /**
     * {@inheritDoc}
     * <p/>
     * If <code>TMNOFLAGS</code> is specified, we create a new transaction
     * context and associate it with this resource.
     * If <code>TMJOIN</code> is specified, this resource should use the
     * same transaction context as another, already known transaction.
     * If <code>TMRESUME</code> is specified, we should resume work on
     * a transaction context that was suspended earlier.
     * All other flags generate an <code>XAException</code> of type
     * <code>XAER_INVAL</code>
     */
    public void start(Xid xid, int flags) throws XAException {
        if (isAssociated()) {
            log.error("Resource already associated with a transaction.");
            throw new XAException(XAException.XAER_PROTO);
        }
        TransactionContext tx;
        if (flags == TMNOFLAGS) {
            tx = (TransactionContext) txGlobal.get(xid);
            if (tx != null) {
                throw new XAException(XAException.XAER_DUPID);
            }
            tx = createTransaction(xid);
        } else if (flags == TMJOIN) {
            tx = (TransactionContext) txGlobal.get(xid);
            if (tx == null) {
                throw new XAException(XAException.XAER_NOTA);
            }
        } else if (flags == TMRESUME) {
            tx = (TransactionContext) txGlobal.get(xid);
            if (tx == null) {
                throw new XAException(XAException.XAER_NOTA);
            }
        } else {
            throw new XAException(XAException.XAER_INVAL);
        }

        associate(tx);
    }

    /**
     * Create a new transaction context.
     * @param xid xid of global transaction.
     * @return transaction context
     */
    private TransactionContext createTransaction(Xid xid) {
        TransactionContext tx = new TransactionContext(txResources, getTransactionTimeout());
        txGlobal.put(xid, tx);
        return tx;
    }

    /**
     * {@inheritDoc}
     * <p/>
     * If <code>TMSUCCESS</code> is specified, we disassociate this session
     * from the transaction specified.
     * If <code>TMFAIL</code> is specified, we disassociate this session from
     * the transaction specified and mark the transaction rollback only.
     * If <code>TMSUSPEND</code> is specified, we disassociate this session
     * from the transaction specified.
     * All other flags generate an <code>XAException</code> of type
     * <code>XAER_INVAL</code>
     */
    public void end(Xid xid, int flags) throws XAException {
        if (!isAssociated()) {
            log.error("Resource not associated with a transaction.");
            throw new XAException(XAException.XAER_PROTO);
        }
        TransactionContext tx = (TransactionContext) txGlobal.get(xid);
        if (tx == null) {
            throw new XAException(XAException.XAER_NOTA);
        }
        if (flags == TMSUCCESS || flags == TMFAIL || flags == TMSUSPEND) {
            associate(null);
        } else {
            throw new XAException(XAException.XAER_INVAL);
        }
    }

    /**
     * {@inheritDoc}
     */
    public int prepare(Xid xid) throws XAException {
        TransactionContext tx = (TransactionContext) txGlobal.get(xid);
        if (tx == null) {
            throw new XAException(XAException.XAER_NOTA);
        }
        tx.prepare();
        return XA_OK;
    }

    /**
     * {@inheritDoc}
     */
    public void commit(Xid xid, boolean onePhase) throws XAException {
        TransactionContext tx = (TransactionContext) txGlobal.get(xid);
        if (tx == null) {
            throw new XAException(XAException.XAER_NOTA);
        }
        if (onePhase) {
            tx.prepare();
        }
        tx.commit();
    }

    /**
     * {@inheritDoc}
     */
    public void rollback(Xid xid) throws XAException {
        TransactionContext tx = (TransactionContext) txGlobal.get(xid);
        if (tx == null) {
            throw new XAException(XAException.XAER_NOTA);
        }
        tx.rollback();
    }

    /**
     * {@inheritDoc}
     * <p/>
     * No recovery support yet.
     */
    public Xid[] recover(int flags) throws XAException {
        return new Xid[0];
    }

    /**
     * {@inheritDoc}
     * <p/>
     * No recovery support yet.
     */
    public void forget(Xid xid) throws XAException {
    }

    /**
     * Associate this session with a global transaction. Internally, set
     * the transaction containing all transaction-local objects to be
     * used when performing item retrieval and store.
     */
    public synchronized void associate(TransactionContext tx) {
        this.tx = tx;

        for (int i = 0; i < txResources.length; i++) {
            InternalXAResource txResource = txResources[i];
            txResource.associate(tx);
        }
    }

    /**
     * Return a flag indicating whether this resource is associated
     * with a transaction.
     *
     * @return <code>true</code> if this resource is associated
     *         with a transaction; otherwise <code>false</code>
     */
    private boolean isAssociated() {
        return tx != null;
    }

    /**
     * Compare two strings for equality. If both are <code>null</code>, this
     * is also considered to be equal.
     */
    private static boolean stringsEqual(String s1, String s2) {
        if (s1 == null) {
            return s2 == null;
        } else {
            return s1.equals(s2);
        }
    }
}
