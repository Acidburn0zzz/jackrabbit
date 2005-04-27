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

import org.apache.jackrabbit.core.lock.LockManager;
import org.apache.jackrabbit.core.nodetype.EffectiveNodeType;
import org.apache.jackrabbit.core.nodetype.NodeDef;
import org.apache.jackrabbit.core.nodetype.NodeTypeRegistry;
import org.apache.jackrabbit.core.nodetype.PropDef;
import org.apache.jackrabbit.core.nodetype.PropDefId;
import org.apache.jackrabbit.core.security.AccessManager;
import org.apache.jackrabbit.core.state.ItemState;
import org.apache.jackrabbit.core.state.ItemStateException;
import org.apache.jackrabbit.core.state.ItemStateManager;
import org.apache.jackrabbit.core.state.NoSuchItemStateException;
import org.apache.jackrabbit.core.state.NodeReferences;
import org.apache.jackrabbit.core.state.NodeReferencesId;
import org.apache.jackrabbit.core.state.NodeState;
import org.apache.jackrabbit.core.state.PropertyState;
import org.apache.jackrabbit.core.state.UpdatableItemStateManager;
import org.apache.jackrabbit.core.util.ReferenceChangeTracker;
import org.apache.jackrabbit.core.util.uuid.UUID;
import org.apache.log4j.Logger;

import javax.jcr.AccessDeniedException;
import javax.jcr.ItemExistsException;
import javax.jcr.ItemNotFoundException;
import javax.jcr.PathNotFoundException;
import javax.jcr.PropertyType;
import javax.jcr.ReferentialIntegrityException;
import javax.jcr.RepositoryException;
import javax.jcr.lock.LockException;
import javax.jcr.nodetype.ConstraintViolationException;
import javax.jcr.version.VersionException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * <code>BatchedItemOperations</code> is an <i>internal</i> helper class that
 * provides both high- and low-level operations directly on the
 * <code>ItemState</code> level.
 */
public class BatchedItemOperations extends ItemValidator implements Constants {

    private static Logger log = Logger.getLogger(BatchedItemOperations.class);

    // flags used by the copy(...) methods
    protected static final int COPY = 0;
    protected static final int CLONE = 1;
    protected static final int CLONE_REMOVE_EXISTING = 2;

    /**
     * option for <code>{@link #checkAddNode}</code> and
     * <code>{@link #checkRemoveNode}</code> methods:<p/>
     * check access rights
     */
    public static final int CHECK_ACCESS = 1;
    /**
     * option for <code>{@link #checkAddNode}</code> and
     * <code>{@link #checkRemoveNode}</code> methods:<p/>
     * check lock status
     */
    public static final int CHECK_LOCK = 2;
    /**
     * option for <code>{@link #checkAddNode}</code> and
     * <code>{@link #checkRemoveNode}</code> methods:<p/>
     * check checked-out status
     */
    public static final int CHECK_VERSIONING = 4;
    /**
     * option for <code>{@link #checkAddNode}</code> and
     * <code>{@link #checkRemoveNode}</code> methods:<p/>
     * check constraints defined in node type
     */
    public static final int CHECK_CONSTRAINTS = 16;
    /**
     * option for <code>{@link #checkRemoveNode}</code> method:<p/>
     * check that target node is not being referenced
     */
    public static final int CHECK_REFERENCES = 8;

    /**
     * wrapped item state manager
     */
    protected final UpdatableItemStateManager stateMgr;
    /**
     * lock manager used for checking locking status
     */
    protected final LockManager lockMgr;
    /**
     * current session used for checking access rights and locking status
     */
    protected final SessionImpl session;

    /**
     * Creates a new <code>BatchedItemOperations</code> instance.
     *
     * @param stateMgr   item state manager
     * @param ntReg      node type registry
     * @param lockMgr    lock manager
     * @param session    current session
     * @param hierMgr    hierarchy manager
     * @param nsResolver namespace resolver
     */
    public BatchedItemOperations(UpdatableItemStateManager stateMgr,
                                 NodeTypeRegistry ntReg,
                                 LockManager lockMgr,
                                 SessionImpl session,
                                 HierarchyManager hierMgr,
                                 NamespaceResolver nsResolver) {
        super(ntReg, hierMgr, nsResolver);
        this.stateMgr = stateMgr;
        this.lockMgr = lockMgr;
        this.session = session;
    }

    //-----------------------------------------< controlling batch operations >
    /**
     * Starts an edit operation on the wrapped state manager.
     * At the end of this operation, either {@link #update} or {@link #cancel}
     * must be invoked.
     *
     * @throws IllegalStateException if the state mananger is already in edit mode
     */
    public void edit() throws IllegalStateException {
        stateMgr.edit();
    }

    /**
     * Store an item state.
     *
     * @param state item state that should be stored
     * @throws IllegalStateException if the manager is not in edit mode.
     */
    public void store(ItemState state) throws IllegalStateException {
        stateMgr.store(state);
    }

    /**
     * Destroy an item state.
     *
     * @param state item state that should be destroyed
     * @throws IllegalStateException if the manager is not in edit mode.
     */
    public void destroy(ItemState state) throws IllegalStateException {
        stateMgr.destroy(state);
    }

    /**
     * End an update operation. This will save all changes made since
     * the last invokation of {@link #edit()}. If this operation fails,
     * no item will have been saved.
     *
     * @throws RepositoryException   if the update operation failed
     * @throws IllegalStateException if the state mananger is not in edit mode
     */
    public void update() throws RepositoryException, IllegalStateException {
        try {
            stateMgr.update();
        } catch (ItemStateException ise) {
            String msg = "update operation failed";
            log.debug(msg, ise);
            throw new RepositoryException(msg, ise);
        }
    }

    /**
     * Cancel an update operation. This will undo all changes made since
     * the last invokation of {@link #edit()}.
     *
     * @throws IllegalStateException if the state mananger is not in edit mode
     */
    public void cancel() throws IllegalStateException {
        stateMgr.cancel();
    }

    //-------------------------------------------< high-level item operations >
    /**
     * Copies the tree at <code>srcPath</code> to the new location at
     * <code>destPath</code>.
     * <p/>
     * <b>Precondition:</b> the state manager needs to be in edit mode.
     *
     * @param srcPath
     * @param destPath
     * @param flag     one of
     *                 <ul>
     *                 <li><code>COPY</code></li>
     *                 <li><code>CLONE</code></li>
     *                 <li><code>CLONE_REMOVE_EXISTING</code></li>
     *                 </ul>
     * @throws ConstraintViolationException
     * @throws AccessDeniedException
     * @throws VersionException
     * @throws PathNotFoundException
     * @throws ItemExistsException
     * @throws LockException
     * @throws RepositoryException
     */
    public void copy(Path srcPath, Path destPath, int flag)
            throws ConstraintViolationException, AccessDeniedException,
            VersionException, PathNotFoundException, ItemExistsException,
            LockException, RepositoryException {
        copy(srcPath, stateMgr, hierMgr, session.getAccessManager(), destPath, flag);
    }

    /**
     * Copies the tree at <code>srcPath</code> retrieved using the specified
     * <code>srcStateMgr</code> to the new location at <code>destPath</code>.
     * <p/>
     * <b>Precondition:</b> the state manager needs to be in edit mode.
     *
     * @param srcPath
     * @param srcStateMgr
     * @param srcHierMgr
     * @param srcAccessMgr
     * @param destPath
     * @param flag         one of
     *                     <ul>
     *                     <li><code>COPY</code></li>
     *                     <li><code>CLONE</code></li>
     *                     <li><code>CLONE_REMOVE_EXISTING</code></li>
     *                     </ul>
     * @throws ConstraintViolationException
     * @throws AccessDeniedException
     * @throws VersionException
     * @throws PathNotFoundException
     * @throws ItemExistsException
     * @throws LockException
     * @throws RepositoryException
     * @throws IllegalStateException        if the state mananger is not in edit mode
     */
    public void copy(Path srcPath,
                     ItemStateManager srcStateMgr,
                     HierarchyManager srcHierMgr,
                     AccessManager srcAccessMgr,
                     Path destPath,
                     int flag)
            throws ConstraintViolationException, AccessDeniedException,
            VersionException, PathNotFoundException, ItemExistsException,
            LockException, RepositoryException, IllegalStateException {

        // check precondition
        if (!stateMgr.inEditMode()) {
            throw new IllegalStateException("not in edit mode");
        }

        // 1. check paths & retrieve state

        NodeState srcState = getNodeState(srcStateMgr, srcHierMgr, srcPath);

        Path.PathElement destName = destPath.getNameElement();
        Path destParentPath = destPath.getAncestor(1);
        NodeState destParentState = getNodeState(destParentPath);
        int ind = destName.getIndex();
        if (ind > 0) {
            // subscript in name element
            String msg = "invalid destination path (subscript in name element is not allowed)";
            log.debug(msg);
            throw new RepositoryException(msg);
        }

        // 2. check access rights, lock status, node type constraints, etc.

        checkAddNode(destParentState, destName.getName(),
                srcState.getNodeTypeName(), CHECK_ACCESS | CHECK_LOCK
                | CHECK_VERSIONING | CHECK_CONSTRAINTS);
        // check read access right on source node using source access manager
        try {
            if (!srcAccessMgr.isGranted(srcState.getId(), AccessManager.READ)) {
                throw new PathNotFoundException(safeGetJCRPath(srcPath));
            }
        } catch (ItemNotFoundException infe) {
            String msg = "internal error: failed to check access rights for "
                    + safeGetJCRPath(srcPath);
            log.debug(msg);
            throw new RepositoryException(msg, infe);
        }

        // 3. do copy operation (modify and store affected states)

        ReferenceChangeTracker refTracker = new ReferenceChangeTracker();

        // create deep copy of source node state
        NodeState newState = copyNodeState(srcState, srcStateMgr, srcAccessMgr,
                destParentState.getUUID(), flag, refTracker);

        // add to new parent
        destParentState.addChildNodeEntry(destName.getName(), newState.getUUID());

        // change definition (id) of new node
        NodeDef newNodeDef =
                findApplicableNodeDefinition(destName.getName(),
                        srcState.getNodeTypeName(), destParentState);
        newState.setDefinitionId(newNodeDef.getId());

        // adjust references that refer to uuid's which have been mapped to
        // newly generated uuid's on copy/clone
        Iterator iter = refTracker.getProcessedReferences();
        while (iter.hasNext()) {
            PropertyState prop = (PropertyState) iter.next();
            // being paranoid...
            if (prop.getType() != PropertyType.REFERENCE) {
                continue;
            }
            boolean modified = false;
            InternalValue[] values = prop.getValues();
            InternalValue[] newVals = new InternalValue[values.length];
            for (int i = 0; i < values.length; i++) {
                InternalValue val = values[i];
                String original = ((UUID) val.internalValue()).toString();
                String adjusted = refTracker.getMappedUUID(original);
                if (adjusted != null) {
                    newVals[i] = InternalValue.create(UUID.fromString(adjusted));
                    modified = true;
                } else {
                    // reference doesn't need adjusting, just copy old value
                    newVals[i] = val;
                }
            }
            if (modified) {
                prop.setValues(newVals);
                stateMgr.store(prop);
            }
        }
        refTracker.clear();

        // store states
        stateMgr.store(newState);
        stateMgr.store(destParentState);
    }

    /**
     * Moves the tree at <code>srcPath</code> to the new location at
     * <code>destPath</code>.
     * <p/>
     * <b>Precondition:</b> the state manager needs to be in edit mode.
     *
     * @param srcPath
     * @param destPath
     * @throws ConstraintViolationException
     * @throws VersionException
     * @throws AccessDeniedException
     * @throws PathNotFoundException
     * @throws ItemExistsException
     * @throws LockException
     * @throws RepositoryException
     * @throws IllegalStateException        if the state mananger is not in edit mode
     */
    public void move(Path srcPath, Path destPath)
            throws ConstraintViolationException, VersionException,
            AccessDeniedException, PathNotFoundException, ItemExistsException,
            LockException, RepositoryException, IllegalStateException {

        // check precondition
        if (!stateMgr.inEditMode()) {
            throw new IllegalStateException("not in edit mode");
        }

        // 1. check paths & retrieve state

        try {
            if (srcPath.isAncestorOf(destPath)) {
                String msg = safeGetJCRPath(destPath)
                        + ": invalid destination path (cannot be descendant of source path)";
                log.debug(msg);
                throw new RepositoryException(msg);
            }
        } catch (MalformedPathException mpe) {
            String msg = "invalid path: " + safeGetJCRPath(destPath);
            log.debug(msg);
            throw new RepositoryException(msg, mpe);
        }

        Path.PathElement srcName = srcPath.getNameElement();
        Path srcParentPath = srcPath.getAncestor(1);
        NodeState target = getNodeState(srcPath);
        NodeState srcParent = getNodeState(srcParentPath);

        Path.PathElement destName = destPath.getNameElement();
        Path destParentPath = destPath.getAncestor(1);
        NodeState destParent = getNodeState(destParentPath);

        int ind = destName.getIndex();
        if (ind > 0) {
            // subscript in name element
            String msg = safeGetJCRPath(destPath)
                    + ": invalid destination path (subscript in name element is not allowed)";
            log.debug(msg);
            throw new RepositoryException(msg);
        }

        // 2. check if target state can be removed from old/added to new parent

        checkRemoveNode(target, (NodeId) srcParent.getId(),
                CHECK_ACCESS | CHECK_LOCK | CHECK_VERSIONING | CHECK_CONSTRAINTS);
        checkAddNode(destParent, destName.getName(),
                target.getNodeTypeName(), CHECK_ACCESS | CHECK_LOCK
                | CHECK_VERSIONING | CHECK_CONSTRAINTS);

        // 3. do move operation (modify and store affected states)

        boolean renameOnly = srcParent.getUUID().equals(destParent.getUUID());

        // add to new parent
        if (!renameOnly) {
            target.addParentUUID(destParent.getUUID());
        }
        destParent.addChildNodeEntry(destName.getName(), target.getUUID());

        // change definition (id) of target node
        NodeDef newTargetDef =
                findApplicableNodeDefinition(destName.getName(),
                        target.getNodeTypeName(), destParent);
        target.setDefinitionId(newTargetDef.getId());

        // remove from old parent
        if (!renameOnly) {
            target.removeParentUUID(srcParent.getUUID());
        }

        int srcNameIndex = srcName.getIndex();
        if (srcNameIndex == 0) {
            srcNameIndex = 1;
        }
        srcParent.removeChildNodeEntry(srcName.getName(), srcNameIndex);

        // store states
        stateMgr.store(target);
        if (renameOnly) {
            stateMgr.store(srcParent);
        } else {
            stateMgr.store(destParent);
            stateMgr.store(srcParent);
        }
    }

    /**
     * Removes the specified node, recursively removing its properties and
     * child nodes.
     * <p/>
     * <b>Precondition:</b> the state manager needs to be in edit mode.
     *
     * @param nodePath
     * @throws ConstraintViolationException
     * @throws AccessDeniedException
     * @throws VersionException
     * @throws LockException
     * @throws ItemNotFoundException
     * @throws ReferentialIntegrityException
     * @throws RepositoryException
     * @throws IllegalStateException
     */
    public void removeNode(Path nodePath)
            throws ConstraintViolationException, AccessDeniedException,
            VersionException, LockException, ItemNotFoundException,
            ReferentialIntegrityException, RepositoryException,
            IllegalStateException {

        // check precondition
        if (!stateMgr.inEditMode()) {
            throw new IllegalStateException("not in edit mode");
        }

        // 1. retrieve affected state

        NodeState target = getNodeState(nodePath);
        NodeId parentId = new NodeId(target.getParentUUID());
        NodeState parent = getNodeState(parentId);

        // 2. check if target state can be removed from parent

        checkRemoveNode(target, parentId,
                CHECK_ACCESS | CHECK_LOCK | CHECK_VERSIONING
                | CHECK_CONSTRAINTS | CHECK_REFERENCES);

        // 3. do remove operation (modify and store affected states)

        // unlink node state from its parent
        unlinkNodeState(target, target.getParentUUID());
        // remove child node entries
        // use temp array to avoid ConcurrentModificationException
        ArrayList tmp =
                new ArrayList(parent.getChildNodeEntries(target.getUUID()));
        // remove from tail to avoid problems with same-name siblings
        for (int i = tmp.size() - 1; i >= 0; i--) {
            NodeState.ChildNodeEntry entry = (NodeState.ChildNodeEntry) tmp.get(i);
            parent.removeChildNodeEntry(entry.getName(), entry.getIndex());
        }
        // store parent
        stateMgr.store(parent);
    }

    //--------------------------------------< misc. high-level helper methods >
    /**
     * Checks if adding a child node called <code>nodeName</code> of node type
     * <code>nodeTypeName</code> to the given parent node is allowed in the
     * current context.
     *
     * @param parentState
     * @param nodeName
     * @param nodeTypeName
     * @param options      bit-wise OR'ed flags specifying the checks that should be
     *                     performed; any combination of the following constants:
     *                     <ul>
     *                     <li><code>{@link #CHECK_ACCESS}</code>: make sure
     *                     current session is granted read & write access on
     *                     parent node</li>
     *                     <li><code>{@link #CHECK_LOCK}</code>: make sure
     *                     there's no foreign lock on parent node</li>
     *                     <li><code>{@link #CHECK_VERSIONING}</code>: make sure
     *                     parent node is checked-out</li>
     *                     <li><code>{@link #CHECK_CONSTRAINTS}</code>:
     *                     make sure no node type constraints would be violated</li>
     *                     <li><code>{@link #CHECK_REFERENCES}</code></li>
     *                     </ul>
     * @throws ConstraintViolationException
     * @throws AccessDeniedException
     * @throws VersionException
     * @throws LockException
     * @throws ItemNotFoundException
     * @throws ItemExistsException
     * @throws RepositoryException
     */
    public void checkAddNode(NodeState parentState, QName nodeName,
                             QName nodeTypeName, int options)
            throws ConstraintViolationException, AccessDeniedException,
            VersionException, LockException, ItemNotFoundException,
            ItemExistsException, RepositoryException {

        Path parentPath = hierMgr.getPath(parentState.getId());

        // 1. locking status

        if ((options & CHECK_LOCK) == CHECK_LOCK) {
            // make sure there's no foreign lock on parent node
            verifyUnlocked(parentPath);
        }

        // 2. versioning status

        if ((options & CHECK_VERSIONING) == CHECK_VERSIONING) {
            // make sure parent node is checked-out
            verifyCheckedOut(parentPath);
        }

        // 3. access rights

        if ((options & CHECK_ACCESS) == CHECK_ACCESS) {
            AccessManager accessMgr = session.getAccessManager();
            // make sure current session is granted read access on parent node
            if (!accessMgr.isGranted(parentState.getId(), AccessManager.READ)) {
                throw new ItemNotFoundException(safeGetJCRPath(parentState.getId()));
            }
            // make sure current session is granted write access on parent node
            if (!accessMgr.isGranted(parentState.getId(), AccessManager.WRITE)) {
                throw new AccessDeniedException(safeGetJCRPath(parentState.getId())
                        + ": not allowed to add child node");
            }
        }

        // 4. node type constraints

        if ((options & CHECK_CONSTRAINTS) == CHECK_CONSTRAINTS) {
            NodeDef parentDef = ntReg.getNodeDef(parentState.getDefinitionId());
            // make sure parent node is not protected
            if (parentDef.isProtected()) {
                throw new ConstraintViolationException(safeGetJCRPath(parentState.getId())
                        + ": cannot add child node to protected parent node");
            }
            // make sure there's an applicable definition for new child node
            EffectiveNodeType entParent = getEffectiveNodeType(parentState);
            entParent.checkAddNodeConstraints(nodeName, nodeTypeName);
            NodeDef newNodeDef =
                    findApplicableNodeDefinition(nodeName, nodeTypeName,
                            parentState);

            // check for name collisions
            if (parentState.hasPropertyEntry(nodeName)) {
                // there's already a property with that name
                throw new ItemExistsException("cannot add child node '"
                        + nodeName.getLocalName() + "' to "
                        + safeGetJCRPath(parentState.getId())
                        + ": colliding with same-named existing property");
            }
            if (parentState.hasChildNodeEntry(nodeName)) {
                // there's already a node with that name...

                // get definition of existing conflicting node
                NodeState.ChildNodeEntry entry = parentState.getChildNodeEntry(nodeName, 1);
                NodeState conflictingState;
                NodeId conflictingId = new NodeId(entry.getUUID());
                try {
                    conflictingState = (NodeState) stateMgr.getItemState(conflictingId);
                } catch (ItemStateException ise) {
                    String msg = "internal error: failed to retrieve state of "
                            + safeGetJCRPath(conflictingId);
                    log.debug(msg);
                    throw new RepositoryException(msg, ise);
                }
                NodeDef conflictingTargetDef =
                        ntReg.getNodeDef(conflictingState.getDefinitionId());
                // check same-name sibling setting of both target and existing node
                if (!conflictingTargetDef.allowsSameNameSiblings()
                        || !newNodeDef.allowsSameNameSiblings()) {
                    throw new ItemExistsException("cannot add child node '"
                            + nodeName.getLocalName() + "' to "
                            + safeGetJCRPath(parentState.getId())
                            + ": colliding with same-named existing node");
                }
            }
        }
    }

    /**
     * Checks if removing the given target node entirely (i.e. unlinking from
     * all its parents) is allowed in the current context.
     *
     * @param targetState
     * @param options     bit-wise OR'ed flags specifying the checks that should be
     *                    performed; any combination of the following constants:
     *                    <ul>
     *                    <li><code>{@link #CHECK_ACCESS}</code>: make sure
     *                    current session is granted read access on parent
     *                    and remove privilege on target node</li>
     *                    <li><code>{@link #CHECK_LOCK}</code>: make sure
     *                    there's no foreign lock on parent node</li>
     *                    <li><code>{@link #CHECK_VERSIONING}</code>: make sure
     *                    parent node is checked-out</li>
     *                    <li><code>{@link #CHECK_CONSTRAINTS}</code>:
     *                    make sure no node type constraints would be violated</li>
     *                    <li><code>{@link #CHECK_REFERENCES}</code>:
     *                    make sure no references exist on target node</li>
     *                    </ul>
     * @throws ConstraintViolationException
     * @throws AccessDeniedException
     * @throws VersionException
     * @throws LockException
     * @throws ItemNotFoundException
     * @throws ReferentialIntegrityException
     * @throws RepositoryException
     */
    public void checkRemoveNode(NodeState targetState, int options)
            throws ConstraintViolationException, AccessDeniedException,
            VersionException, LockException, ItemNotFoundException,
            ReferentialIntegrityException, RepositoryException {
        List parentUUIDs = targetState.getParentUUIDs();
        Iterator iter = parentUUIDs.iterator();
        while (iter.hasNext()) {
            NodeId parentId = new NodeId((String) iter.next());
            checkRemoveNode(targetState, parentId, options);
        }
    }

    /**
     * Checks if removing the given target node from the specifed parent
     * is allowed in the current context.
     *
     * @param targetState
     * @param parentId
     * @param options     bit-wise OR'ed flags specifying the checks that should be
     *                    performed; any combination of the following constants:
     *                    <ul>
     *                    <li><code>{@link #CHECK_ACCESS}</code>: make sure
     *                    current session is granted read access on parent
     *                    and remove privilege on target node</li>
     *                    <li><code>{@link #CHECK_LOCK}</code>: make sure
     *                    there's no foreign lock on parent node</li>
     *                    <li><code>{@link #CHECK_VERSIONING}</code>: make sure
     *                    parent node is checked-out</li>
     *                    <li><code>{@link #CHECK_CONSTRAINTS}</code>:
     *                    make sure no node type constraints would be violated</li>
     *                    <li><code>{@link #CHECK_REFERENCES}</code>:
     *                    make sure no references exist on target node</li>
     *                    </ul>
     * @throws ConstraintViolationException
     * @throws AccessDeniedException
     * @throws VersionException
     * @throws LockException
     * @throws ItemNotFoundException
     * @throws ReferentialIntegrityException
     * @throws RepositoryException
     */
    public void checkRemoveNode(NodeState targetState, NodeId parentId,
                                int options)
            throws ConstraintViolationException, AccessDeniedException,
            VersionException, LockException, ItemNotFoundException,
            ReferentialIntegrityException, RepositoryException {

        if (targetState.getParentUUID() == null) {
            // root or orphaned node
            throw new ConstraintViolationException("cannot remove root node");
        }
        NodeId targetId = (NodeId) targetState.getId();
        NodeState parentState = getNodeState(parentId);
        Path parentPath = hierMgr.getPath(parentId);

        // 1. locking status

        if ((options & CHECK_LOCK) == CHECK_LOCK) {
            // make sure there's no foreign lock on parent node
            verifyUnlocked(parentPath);
        }

        // 2. versioning status

        if ((options & CHECK_VERSIONING) == CHECK_VERSIONING) {
            // make sure parent node is checked-out
            verifyCheckedOut(parentPath);
        }

        // 3. access rights

        if ((options & CHECK_ACCESS) == CHECK_ACCESS) {
            AccessManager accessMgr = session.getAccessManager();
            try {
                // make sure current session is granted read access on parent node
                if (!accessMgr.isGranted(targetId, AccessManager.READ)) {
                    throw new PathNotFoundException(safeGetJCRPath(targetId));
                }
                // make sure current session is allowed to remove target node
                if (!accessMgr.isGranted(targetId, AccessManager.REMOVE)) {
                    throw new AccessDeniedException(safeGetJCRPath(targetId)
                            + ": not allowed to remove node");
                }
            } catch (ItemNotFoundException infe) {
                String msg = "internal error: failed to check access rights for "
                        + safeGetJCRPath(targetId);
                log.debug(msg);
                throw new RepositoryException(msg, infe);
            }
        }

        // 4. node type constraints

        if ((options & CHECK_CONSTRAINTS) == CHECK_CONSTRAINTS) {
            NodeDef parentDef = ntReg.getNodeDef(parentState.getDefinitionId());
            if (parentDef.isProtected()) {
                throw new ConstraintViolationException(safeGetJCRPath(parentId)
                        + ": cannot remove child node of protected parent node");
            }
            NodeDef targetDef = ntReg.getNodeDef(targetState.getDefinitionId());
            if (targetDef.isMandatory()) {
                throw new ConstraintViolationException(safeGetJCRPath(targetId)
                        + ": cannot remove mandatory node");
            }
            if (targetDef.isProtected()) {
                throw new ConstraintViolationException(safeGetJCRPath(targetId)
                        + ": cannot remove protected node");
            }
        }

        // 5. referential integrity

        if ((options & CHECK_REFERENCES) == CHECK_REFERENCES) {
            EffectiveNodeType ent = getEffectiveNodeType(targetState);
            if (ent.includesNodeType(MIX_REFERENCEABLE)) {
                NodeReferencesId refsId = new NodeReferencesId(targetState.getUUID());
                if (stateMgr.hasNodeReferences(refsId)) {
                    try {
                        NodeReferences refs = stateMgr.getNodeReferences(refsId);
                        if (refs.hasReferences()) {
                            throw new ReferentialIntegrityException(safeGetJCRPath(targetId)
                                    + ": cannot remove node with references");
                        }
                    } catch (ItemStateException ise) {
                        String msg = "internal error: failed to check references on "
                                + safeGetJCRPath(targetId);
                        log.error(msg, ise);
                        throw new RepositoryException(msg, ise);
                    }
                }
            }
        }
    }

    /**
     * Verifies that the node at <code>nodePath</code> is writable. The
     * following conditions must hold true:
     * <ul>
     * <li>the node must exist</li>
     * <li>the current session must be granted read & write access on it</li>
     * <li>the node must not be locked by another session</li>
     * <li>the node must not be checked-in</li>
     * <li>the node must not be protected</li>
     * </ul>
     *
     * @param nodePath path of node to check
     * @throws PathNotFoundException        if no node exists at
     *                                      <code>nodePath</code> of the current
     *                                      session is not granted read access
     *                                      to the specified path
     * @throws AccessDeniedException        if write access to the specified
     *                                      path is not allowed
     * @throws ConstraintViolationException if the node at <code>nodePath</code>
     *                                      is protected
     * @throws VersionException             if the node at <code>nodePath</code>
     *                                      is checked-in
     * @throws LockException                if the node at <code>nodePath</code>
     *                                      is locked by another session
     * @throws RepositoryException          if another error occurs
     */
    public void verifyCanWrite(Path nodePath)
            throws PathNotFoundException, AccessDeniedException,
            ConstraintViolationException, VersionException, LockException,
            RepositoryException {

        NodeState node = getNodeState(nodePath);

        // access rights
        AccessManager accessMgr = session.getAccessManager();
        // make sure current session is granted read access on node
        if (!accessMgr.isGranted(node.getId(), AccessManager.READ)) {
            throw new PathNotFoundException(safeGetJCRPath(node.getId()));
        }
        // make sure current session is granted write access on node
        if (!accessMgr.isGranted(node.getId(), AccessManager.WRITE)) {
            throw new AccessDeniedException(safeGetJCRPath(node.getId())
                    + ": not allowed to modify node");
        }

        // locking status
        verifyUnlocked(nodePath);

        // node type constraints
        verifyNotProtected(nodePath);

        // versioning status
        verifyCheckedOut(nodePath);
    }

    /**
     * Verifies that the node at <code>nodePath</code> can be read. The
     * following conditions must hold true:
     * <ul>
     * <li>the node must exist</li>
     * <li>the current session must be granted read access on it</li>
     * </ul>
     *
     * @param nodePath path of node to check
     * @throws PathNotFoundException if no node exists at
     *                               <code>nodePath</code> of the current
     *                               session is not granted read access
     *                               to the specified path
     * @throws RepositoryException   if another error occurs
     */
    public void verifyCanRead(Path nodePath)
            throws PathNotFoundException, RepositoryException {
        NodeState node = getNodeState(nodePath);

        // access rights
        AccessManager accessMgr = session.getAccessManager();
        // make sure current session is granted read access on node
        if (!accessMgr.isGranted(node.getId(), AccessManager.READ)) {
            throw new PathNotFoundException(safeGetJCRPath(node.getId()));
        }
    }

    /**
     * Helper method that finds the applicable definition for a child node with
     * the given name and node type in the parent node's node type and
     * mixin types.
     *
     * @param name
     * @param nodeTypeName
     * @param parentState
     * @return a <code>NodeDef</code>
     * @throws ConstraintViolationException if no applicable child node definition
     *                                      could be found
     * @throws RepositoryException          if another error occurs
     */
    public NodeDef findApplicableNodeDefinition(QName name,
                                                QName nodeTypeName,
                                                NodeState parentState)
            throws RepositoryException, ConstraintViolationException {
        EffectiveNodeType entParent = getEffectiveNodeType(parentState);
        return entParent.getApplicableChildNodeDef(name, nodeTypeName);
    }

    /**
     * Helper method that finds the applicable definition for a property with
     * the given name, type and multiValued characteristic in the parent node's
     * node type and mixin types.
     *
     * @param name
     * @param type
     * @param multiValued
     * @param parentState
     * @return a <code>PropDef</code>
     * @throws ConstraintViolationException if no applicable property definition
     *                                      could be found
     * @throws RepositoryException          if another error occurs
     */
    public PropDef findApplicablePropertyDefinition(QName name,
                                                    int type,
                                                    boolean multiValued,
                                                    NodeState parentState)
            throws RepositoryException, ConstraintViolationException {
        EffectiveNodeType entParent = getEffectiveNodeType(parentState);
        return entParent.getApplicablePropertyDef(name, type, multiValued);
    }

    //--------------------------------------------< low-level item operations >
    /**
     * Creates a new node.
     * <p/>
     * Note that access rights are <b><i>not</i></b> enforced!
     * <p/>
     * <b>Precondition:</b> the state manager needs to be in edit mode.
     *
     * @param parent
     * @param nodeName
     * @param nodeTypeName
     * @param mixinNames
     * @param uuid
     * @return
     * @throws ItemExistsException
     * @throws ConstraintViolationException
     * @throws RepositoryException
     * @throws IllegalStateException        if the state mananger is not in edit mode
     */
    public NodeState createNodeState(NodeState parent,
                                     QName nodeName,
                                     QName nodeTypeName,
                                     QName[] mixinNames,
                                     String uuid)
            throws ItemExistsException, ConstraintViolationException,
            RepositoryException, IllegalStateException {

        // check precondition
        if (!stateMgr.inEditMode()) {
            throw new IllegalStateException("not in edit mode");
        }

        NodeDef def = findApplicableNodeDefinition(nodeName, nodeTypeName, parent);
        return createNodeState(parent, nodeName, nodeTypeName, mixinNames, uuid, def);
    }

    /**
     * Creates a new node based on the given definition.
     * <p/>
     * Note that access rights are <b><i>not</i></b> enforced!
     * <p/>
     * <b>Precondition:</b> the state manager needs to be in edit mode.
     *
     * @param parent
     * @param nodeName
     * @param nodeTypeName
     * @param mixinNames
     * @param uuid
     * @param def
     * @return
     * @throws ItemExistsException
     * @throws ConstraintViolationException
     * @throws RepositoryException
     * @throws IllegalStateException
     */
    public NodeState createNodeState(NodeState parent,
                                     QName nodeName,
                                     QName nodeTypeName,
                                     QName[] mixinNames,
                                     String uuid,
                                     NodeDef def)
            throws ItemExistsException, ConstraintViolationException,
            RepositoryException, IllegalStateException {

        // check for name collisions with existing properties
        if (parent.hasPropertyEntry(nodeName)) {
            String msg = "there's already a property with name " + nodeName;
            log.debug(msg);
            throw new RepositoryException(msg);
        }
        // check for name collisions with existing nodes
        if (!def.allowsSameNameSiblings() && parent.hasChildNodeEntry(nodeName)) {
            NodeId id = new NodeId(parent.getChildNodeEntry(nodeName, 1).getUUID());
            throw new ItemExistsException(safeGetJCRPath(id));
        }
        if (uuid == null) {
            // create new uuid
            uuid = UUID.randomUUID().toString();    // create new version 4 uuid
        }
        if (nodeTypeName == null) {
            // no primary node type specified,
            // try default primary type from definition
            nodeTypeName = def.getDefaultPrimaryType();
            if (nodeTypeName == null) {
                String msg = "an applicable node type could not be determined for "
                        + nodeName;
                log.debug(msg);
                throw new ConstraintViolationException(msg);
            }
        }
        NodeState node = stateMgr.createNew(uuid, nodeTypeName, parent.getUUID());
        if (mixinNames != null && mixinNames.length > 0) {
            node.setMixinTypeNames(new HashSet(Arrays.asList(mixinNames)));
        }
        node.setDefinitionId(def.getId());

        // now add new child node entry to parent
        parent.addChildNodeEntry(nodeName, node.getUUID());

        EffectiveNodeType ent = getEffectiveNodeType(node);

        if (!node.getMixinTypeNames().isEmpty()) {
            // create jcr:mixinTypes property
            PropDef pd = ent.getApplicablePropertyDef(JCR_MIXINTYPES,
                    PropertyType.NAME, true);
            createPropertyState(node, pd.getName(), pd.getRequiredType(), pd);
        }

        // add 'auto-create' properties defined in node type
        PropDef[] pda = ent.getAutoCreatePropDefs();
        for (int i = 0; i < pda.length; i++) {
            PropDef pd = pda[i];
            createPropertyState(node, pd.getName(), pd.getRequiredType(), pd);
        }

        // recursively add 'auto-create' child nodes defined in node type
        NodeDef[] nda = ent.getAutoCreateNodeDefs();
        for (int i = 0; i < nda.length; i++) {
            NodeDef nd = nda[i];
            createNodeState(node, nd.getName(), nd.getDefaultPrimaryType(),
                    null, null, nd);
        }

        // store node
        stateMgr.store(node);
        // store parent
        stateMgr.store(parent);

        return node;
    }

    /**
     * Creates a new property.
     * <p/>
     * Note that access rights are <b><i>not</i></b> enforced!
     * <p/>
     * <b>Precondition:</b> the state manager needs to be in edit mode.
     *
     * @param parent
     * @param propName
     * @param type
     * @param numValues
     * @return
     * @throws ItemExistsException
     * @throws ConstraintViolationException
     * @throws RepositoryException
     * @throws IllegalStateException        if the state mananger is not in edit mode
     */
    public PropertyState createPropertyState(NodeState parent,
                                             QName propName,
                                             int type,
                                             int numValues)
            throws ItemExistsException, ConstraintViolationException,
            RepositoryException, IllegalStateException {

        // check precondition
        if (!stateMgr.inEditMode()) {
            throw new IllegalStateException("not in edit mode");
        }

        // find applicable definition
        PropDef def;
        // multi- or single-valued property?
        if (numValues == 1) {
            // could be single- or multi-valued (n == 1)
            try {
                // try single-valued
                def = findApplicablePropertyDefinition(propName,
                        type, false, parent);
            } catch (ConstraintViolationException cve) {
                // try multi-valued
                def = findApplicablePropertyDefinition(propName,
                        type, true, parent);
            }
        } else {
            // can only be multi-valued (n == 0 || n > 1)
            def = findApplicablePropertyDefinition(propName,
                    type, true, parent);
        }
        return createPropertyState(parent, propName, type, def);
    }

    /**
     * Creates a new property based on the given definition.
     * <p/>
     * Note that access rights are <b><i>not</i></b> enforced!
     * <p/>
     * <b>Precondition:</b> the state manager needs to be in edit mode.
     *
     * @param parent
     * @param propName
     * @param type
     * @param def
     * @return
     * @throws ItemExistsException
     * @throws RepositoryException
     */
    public PropertyState createPropertyState(NodeState parent,
                                             QName propName,
                                             int type,
                                             PropDef def)
            throws ItemExistsException, RepositoryException {
        // check for name collisions with existing child nodes
        if (parent.hasChildNodeEntry(propName)) {
            String msg = "there's already a child node with name " + propName;
            log.debug(msg);
            throw new RepositoryException(msg);
        }

        // check for name collisions with existing properties
        if (parent.hasPropertyEntry(propName)) {
            PropertyId id = new PropertyId(parent.getUUID(),
                    parent.getPropertyEntry(propName).getName());
            throw new ItemExistsException(safeGetJCRPath(id));
        }

        // create property
        PropertyState prop = stateMgr.createNew(propName, parent.getUUID());

        prop.setDefinitionId(def.getId());
        if (def.getRequiredType() != PropertyType.UNDEFINED) {
            prop.setType(def.getRequiredType());
        } else if (type != PropertyType.UNDEFINED) {
            prop.setType(type);
        } else {
            prop.setType(PropertyType.STRING);
        }
        prop.setMultiValued(def.isMultiple());

        // compute system generated values if necessary
        InternalValue[] genValues =
                computeSystemGeneratedPropertyValues(parent, def);
        if (genValues != null) {
            prop.setValues(genValues);
        } else if (def.getDefaultValues() != null) {
            prop.setValues(def.getDefaultValues());
        }

        // now add new property entry to parent
        parent.addPropertyEntry(propName);
        // store parent
        stateMgr.store(parent);

        return prop;
    }

    /**
     * Unlinks the specified node state from all its parents and recursively
     * removes it including its properties and child nodes.
     * <p/>
     * Note that access rights are <b><i>not</i></b> enforced!
     *
     * @param targetState
     * @throws RepositoryException if an error occurs
     */
    public void removeNodeState(NodeState targetState)
            throws RepositoryException {

        // copy list to avoid ConcurrentModificationException
        ArrayList parentUUIDs = new ArrayList(targetState.getParentUUIDs());
        Iterator iter = parentUUIDs.iterator();
        while (iter.hasNext()) {
            String parentUUID = (String) iter.next();
            NodeId parentId = new NodeId(parentUUID);

            // unlink node state from this parent
            unlinkNodeState(targetState, parentUUID);

            // remove child node entries
            NodeState parent = getNodeState(parentId);
            // use temp array to avoid ConcurrentModificationException
            ArrayList tmp =
                    new ArrayList(parent.getChildNodeEntries(targetState.getUUID()));
            // remove from tail to avoid problems with same-name siblings
            for (int i = tmp.size() - 1; i >= 0; i--) {
                NodeState.ChildNodeEntry entry = (NodeState.ChildNodeEntry) tmp.get(i);
                parent.removeChildNodeEntry(entry.getName(), entry.getIndex());
            }
            // store parent
            stateMgr.store(parent);
        }
    }

    /**
     * Retrieves the state of the node at the given path.
     * <p/>
     * Note that access rights are <b><i>not</i></b> enforced!
     *
     * @param nodePath
     * @return
     * @throws PathNotFoundException
     * @throws RepositoryException
     */
    public NodeState getNodeState(Path nodePath)
            throws PathNotFoundException, RepositoryException {
        return getNodeState(stateMgr, hierMgr, nodePath);
    }

    /**
     * Retrieves the state of the node with the given id.
     * <p/>
     * Note that access rights are <b><i>not</i></b> enforced!
     *
     * @param id
     * @return
     * @throws ItemNotFoundException
     * @throws RepositoryException
     */
    public NodeState getNodeState(NodeId id)
            throws ItemNotFoundException, RepositoryException {
        return (NodeState) getItemState(stateMgr, id);
    }

    /**
     * Retrieves the state of the item with the given id.
     * <p/>
     * Note that access rights are <b><i>not</i></b> enforced!
     *
     * @param id
     * @return
     * @throws ItemNotFoundException
     * @throws RepositoryException
     */
    public ItemState getItemState(ItemId id)
            throws ItemNotFoundException, RepositoryException {
        return getItemState(stateMgr, id);
    }

    //----------------------------------------------------< protected methods >
    /**
     * Verifies that the node at <code>nodePath</code> is checked-out; throws a
     * <code>VersionException</code> if that's not the case.
     * <p/>
     * A node is considered <i>checked-out</i> if it is versionable and
     * checked-out, or is non-versionable but its nearest versionable ancestor
     * is checked-out, or is non-versionable and there are no versionable
     * ancestors.
     *
     * @param nodePath
     * @throws PathNotFoundException
     * @throws VersionException
     * @throws RepositoryException
     */
    protected void verifyCheckedOut(Path nodePath)
            throws PathNotFoundException, VersionException, RepositoryException {
        // search nearest ancestor that is versionable, start with node at nodePath
        /**
         * FIXME should not only rely on existence of jcr:isCheckedOut property
         * but also verify that node.isNodeType("mix:versionable")==true;
         * this would have a negative impact on performance though...
         */
        NodeState nodeState = getNodeState(nodePath);
        while (!nodeState.hasPropertyEntry(JCR_ISCHECKEDOUT)) {
            if (nodePath.denotesRoot()) {
                return;
            }
            nodePath = nodePath.getAncestor(1);
            nodeState = getNodeState(nodePath);
        }
        PropertyId propId =
                new PropertyId(nodeState.getUUID(), JCR_ISCHECKEDOUT);
        PropertyState propState;
        try {
            propState = (PropertyState) stateMgr.getItemState(propId);
        } catch (ItemStateException ise) {
            String msg = "internal error: failed to retrieve state of "
                    + safeGetJCRPath(propId);
            log.debug(msg);
            throw new RepositoryException(msg, ise);
        }
        boolean checkedOut = ((Boolean) propState.getValues()[0].internalValue()).booleanValue();
        if (!checkedOut) {
            throw new VersionException(safeGetJCRPath(nodePath) + " is checked-in");
        }
    }

    /**
     * Verifies that the node at <code>nodePath</code> is not locked by
     * somebody else than the current session.
     *
     * @param nodePath path of node to check
     * @throws PathNotFoundException
     * @throws LockException         if write access to the specified path is not allowed
     * @throws RepositoryException   if another error occurs
     */
    protected void verifyUnlocked(Path nodePath)
            throws LockException, RepositoryException {
        // make sure there's no foreign lock on node at nodePath
        lockMgr.checkLock(nodePath, session);
    }

    /**
     * Verifies that the node at <code>nodePath</code> is not protected.
     *
     * @param nodePath path of node to check
     * @throws PathNotFoundException        if no node exists at <code>nodePath</code>
     * @throws ConstraintViolationException if write access to the specified
     *                                      path is not allowed
     * @throws RepositoryException          if another error occurs
     */
    protected void verifyNotProtected(Path nodePath)
            throws PathNotFoundException, ConstraintViolationException,
            RepositoryException {
        NodeState node = getNodeState(nodePath);
        NodeDef parentDef = ntReg.getNodeDef(node.getDefinitionId());
        if (parentDef.isProtected()) {
            throw new ConstraintViolationException(safeGetJCRPath(nodePath)
                    + ": node is protected");
        }
    }

    /**
     * Retrieves the state of the node at <code>nodePath</code> using the given
     * item state manager.
     * <p/>
     * Note that access rights are <b><i>not</i></b> enforced!
     *
     * @param srcStateMgr
     * @param srcHierMgr
     * @param nodePath
     * @return
     * @throws PathNotFoundException
     * @throws RepositoryException
     */
    protected NodeState getNodeState(ItemStateManager srcStateMgr,
                                     HierarchyManager srcHierMgr,
                                     Path nodePath)
            throws PathNotFoundException, RepositoryException {
        try {
            ItemId id = srcHierMgr.resolvePath(nodePath);
            if (!id.denotesNode()) {
                throw new PathNotFoundException(safeGetJCRPath(nodePath));
            }
            return (NodeState) getItemState(srcStateMgr, id);
        } catch (ItemNotFoundException infe) {
            throw new PathNotFoundException(safeGetJCRPath(nodePath));
        }
    }

    /**
     * Retrieves the state of the item with the specified id using the given
     * item state manager.
     * <p/>
     * Note that access rights are <b><i>not</i></b> enforced!
     *
     * @param srcStateMgr
     * @param id
     * @return
     * @throws ItemNotFoundException
     * @throws RepositoryException
     */
    protected ItemState getItemState(ItemStateManager srcStateMgr, ItemId id)
            throws ItemNotFoundException, RepositoryException {
        try {
            return srcStateMgr.getItemState(id);
        } catch (NoSuchItemStateException nsise) {
            throw new ItemNotFoundException(safeGetJCRPath(id));
        } catch (ItemStateException ise) {
            String msg = "internal error: failed to retrieve state of "
                    + safeGetJCRPath(id);
            log.debug(msg);
            throw new RepositoryException(msg, ise);
        }
    }

    //------------------------------------------------------< private methods >
    /**
     * Computes the values of well-known system (i.e. protected) properties.
     * todo: duplicate code in NodeImpl: consolidate and delegate to NodeTypeInstanceHandler
     *
     * @param parent
     * @param def
     * @return
     */
    private InternalValue[] computeSystemGeneratedPropertyValues(NodeState parent,
                                                                 PropDef def) {
        InternalValue[] genValues = null;

        /**
         * todo: need to come up with some callback mechanism for applying system generated values
         * (e.g. using a NodeTypeInstanceHandler interface)
         */

        // compute system generated values
        QName declaringNT = def.getDeclaringNodeType();
        QName name = def.getName();
        if (MIX_REFERENCEABLE.equals(declaringNT)) {
            // mix:referenceable node type
            if (JCR_UUID.equals(name)) {
                // jcr:uuid property
                genValues = new InternalValue[]{InternalValue.create(parent.getUUID())};
            }
        } else if (NT_BASE.equals(declaringNT)) {
            // nt:base node type
            if (JCR_PRIMARYTYPE.equals(name)) {
                // jcr:primaryType property
                genValues = new InternalValue[]{InternalValue.create(parent.getNodeTypeName())};
            } else if (JCR_MIXINTYPES.equals(name)) {
                // jcr:mixinTypes property
                Set mixins = parent.getMixinTypeNames();
                ArrayList values = new ArrayList(mixins.size());
                Iterator iter = mixins.iterator();
                while (iter.hasNext()) {
                    values.add(InternalValue.create((QName) iter.next()));
                }
                genValues = (InternalValue[]) values.toArray(new InternalValue[values.size()]);
            }
        } else if (NT_HIERARCHYNODE.equals(declaringNT)) {
            // nt:hierarchyNode node type
            if (JCR_CREATED.equals(name)) {
                // jcr:created property
                genValues = new InternalValue[]{InternalValue.create(Calendar.getInstance())};
            }
        } else if (NT_RESOURCE.equals(declaringNT)) {
            // nt:resource node type
            if (JCR_LASTMODIFIED.equals(name)) {
                // jcr:lastModified property
                genValues = new InternalValue[]{InternalValue.create(Calendar.getInstance())};
            }
        } else if (NT_VERSION.equals(declaringNT)) {
            // nt:version node type
            if (JCR_CREATED.equals(name)) {
                // jcr:created property
                genValues = new InternalValue[]{InternalValue.create(Calendar.getInstance())};
            }
        }

        return genValues;
    }

    /**
     * Unlinks the given node state from the specified parent i.e. removes
     * <code>parentUUID</code> from its list of parents. If as a result
     * the given node state would be orphaned it will be recursively removed
     * including its properties and child nodes.
     * <p/>
     * Note that the child node entry refering to <code>targetState</code> is
     * <b><i>not</i></b> automatically removed from <code>targetState</code>'s
     * parent denoted by <code>parentUUID</code>.
     *
     * @param targetState
     * @param parentUUID
     * @throws RepositoryException if an error occurs
     */
    private void unlinkNodeState(NodeState targetState, String parentUUID)
            throws RepositoryException {

        // check if this node state would be orphaned after unlinking it from parent
        ArrayList parentUUIDs = new ArrayList(targetState.getParentUUIDs());
        parentUUIDs.remove(parentUUID);
        boolean orphaned = parentUUIDs.isEmpty();

        if (orphaned) {
            // remove child nodes
            // use temp array to avoid ConcurrentModificationException
            ArrayList tmp = new ArrayList(targetState.getChildNodeEntries());
            // remove from tail to avoid problems with same-name siblings
            for (int i = tmp.size() - 1; i >= 0; i--) {
                NodeState.ChildNodeEntry entry = (NodeState.ChildNodeEntry) tmp.get(i);
                NodeId nodeId = new NodeId(entry.getUUID());
                try {
                    NodeState nodeState = (NodeState) stateMgr.getItemState(nodeId);
                    // check if child node can be removed
                    // (access rights, locking & versioning status)
                    checkRemoveNode(nodeState, (NodeId) targetState.getId(),
                            CHECK_ACCESS | CHECK_LOCK | CHECK_VERSIONING);
                    // unlink child node (recursive)
                    unlinkNodeState(nodeState, targetState.getUUID());
                } catch (ItemStateException ise) {
                    String msg = "internal error: failed to retrieve state of "
                            + nodeId;
                    log.debug(msg);
                    throw new RepositoryException(msg, ise);
                }
                // remove child node entry
                targetState.removeChildNodeEntry(entry.getName(), entry.getIndex());
            }

            // remove properties
            // use temp array to avoid ConcurrentModificationException
            tmp = new ArrayList(targetState.getPropertyEntries());
            for (int i = 0; i < tmp.size(); i++) {
                NodeState.PropertyEntry entry = (NodeState.PropertyEntry) tmp.get(i);
                PropertyId propId =
                        new PropertyId(targetState.getUUID(), entry.getName());
                try {
                    PropertyState propState =
                            (PropertyState) stateMgr.getItemState(propId);
                    // remove property entry
                    targetState.removePropertyEntry(propId.getName());
                    // destroy property state
                    stateMgr.destroy(propState);
                } catch (ItemStateException ise) {
                    String msg = "internal error: failed to retrieve state of "
                            + propId;
                    log.debug(msg);
                    throw new RepositoryException(msg, ise);
                }
            }
        }

        // now actually do unlink target state from specified parent state
        // (i.e. remove uuid of parent state from target state's parent list)
        targetState.removeParentUUID(parentUUID);

        if (orphaned) {
            // destroy target state (pass overlayed state since target state
            // might have been modified during unlinking)
            stateMgr.destroy(targetState.getOverlayedState());
        } else {
            // store target state
            stateMgr.store(targetState);
        }
    }

    /**
     * Recursively copies the specified node state including its properties and
     * child nodes.
     *
     * @param srcState
     * @param srcStateMgr
     * @param srcAccessMgr
     * @param destParentUUID
     * @param flag           one of
     *                       <ul>
     *                       <li><code>COPY</code></li>
     *                       <li><code>CLONE</code></li>
     *                       <li><code>CLONE_REMOVE_EXISTING</code></li>
     *                       </ul>
     * @param refTracker     tracks uuid mappings and processed reference properties
     * @return a deep copy of the given node state and its children
     * @throws RepositoryException if an error occurs
     */
    private NodeState copyNodeState(NodeState srcState,
                                    ItemStateManager srcStateMgr,
                                    AccessManager srcAccessMgr,
                                    String destParentUUID,
                                    int flag,
                                    ReferenceChangeTracker refTracker)
            throws RepositoryException {

        NodeState newState;
        try {
            String uuid;
            NodeId id;
            EffectiveNodeType ent = getEffectiveNodeType(srcState);
            boolean referenceable = ent.includesNodeType(MIX_REFERENCEABLE);
            switch (flag) {
                case COPY:
                    // always create new uuid
                    uuid = UUID.randomUUID().toString();    // create new version 4 uuid
                    if (referenceable) {
                        // remember uuid mapping
                        refTracker.mappedUUID(srcState.getUUID(), uuid);
                    }
                    break;
                case CLONE:
                    if (!referenceable) {
                        // non-referenceable node: always create new uuid
                        uuid = UUID.randomUUID().toString();    // create new version 4 uuid
                        break;
                    }
                    // use same uuid as source node
                    uuid = srcState.getUUID();
                    id = new NodeId(uuid);
                    if (stateMgr.hasItemState(id)) {
                        // node with this uuid already exists
                        throw new ItemExistsException(safeGetJCRPath(id));
                    }
                    break;
                case CLONE_REMOVE_EXISTING:
                    if (!referenceable) {
                        // non-referenceable node: always create new uuid
                        uuid = UUID.randomUUID().toString();    // create new version 4 uuid
                        break;
                    }
                    // use same uuid as source node
                    uuid = srcState.getUUID();
                    id = new NodeId(uuid);
                    if (stateMgr.hasItemState(id)) {
                        NodeState existingState = (NodeState) stateMgr.getItemState(id);
                        // make sure existing node is not the parent
                        // or an ancestor thereof
                        NodeId newParentId = new NodeId(destParentUUID);
                        Path p0 = hierMgr.getPath(newParentId);
                        Path p1 = hierMgr.getPath(id);
                        try {
                            if (p1.equals(p0) || p1.isAncestorOf(p0)) {
                                String msg = "cannot remove ancestor node";
                                log.debug(msg);
                                throw new RepositoryException(msg);
                            }
                        } catch (MalformedPathException mpe) {
                            // should never get here...
                            String msg = "internal error: failed to determine degree of relationship";
                            log.error(msg, mpe);
                            throw new RepositoryException(msg, mpe);
                        }

                        // check if existing can be removed
                        checkRemoveNode(existingState, CHECK_ACCESS | CHECK_LOCK
                                | CHECK_VERSIONING | CHECK_CONSTRAINTS);

                        // do remove existing
                        removeNodeState(existingState);
                    }
                    break;
                default:
                    throw new IllegalArgumentException("unknown flag");
            }
            newState = stateMgr.createNew(uuid, srcState.getNodeTypeName(), destParentUUID);
            // copy node state
            // @todo special handling required for nodes with special semantics (e.g. those defined by mix:versionable, et.al.)
            // FIXME delegate to 'node type instance handler'
            newState.setMixinTypeNames(srcState.getMixinTypeNames());
            newState.setDefinitionId(srcState.getDefinitionId());
            // copy child nodes
            Iterator iter = srcState.getChildNodeEntries().iterator();
            while (iter.hasNext()) {
                NodeState.ChildNodeEntry entry = (NodeState.ChildNodeEntry) iter.next();
                NodeId nodeId = new NodeId(entry.getUUID());
                if (!srcAccessMgr.isGranted(nodeId, AccessManager.READ)) {
                    continue;
                }
                NodeState srcChildState = (NodeState) srcStateMgr.getItemState(nodeId);
                // recursive copying of child node
                NodeState newChildState = copyNodeState(srcChildState,
                        srcStateMgr, srcAccessMgr, uuid, flag, refTracker);
                // store new child node
                stateMgr.store(newChildState);
                // add new child node entry to new node
                newState.addChildNodeEntry(entry.getName(), newChildState.getUUID());
            }
            // copy properties
            iter = srcState.getPropertyEntries().iterator();
            while (iter.hasNext()) {
                NodeState.PropertyEntry entry = (NodeState.PropertyEntry) iter.next();
                PropertyId propId = new PropertyId(srcState.getUUID(), entry.getName());
                if (!srcAccessMgr.isGranted(propId, AccessManager.READ)) {
                    continue;
                }
                PropertyState srcChildState =
                        (PropertyState) srcStateMgr.getItemState(propId);
                PropertyState newChildState =
                        copyPropertyState(srcChildState, uuid, entry.getName());
                if (newChildState.getType() == PropertyType.REFERENCE) {
                    refTracker.processedReference(newChildState);
                }
                // store new property
                stateMgr.store(newChildState);
                // add new property entry to new node
                newState.addPropertyEntry(entry.getName());
            }
            return newState;
        } catch (ItemStateException ise) {
            String msg = "internal error: failed to copy state of " + srcState.getId();
            log.debug(msg);
            throw new RepositoryException(msg, ise);
        }
    }

    /**
     * Copies the specified property state.
     *
     * @param srcState
     * @param parentUUID
     * @param propName
     * @return
     * @throws RepositoryException
     */
    private PropertyState copyPropertyState(PropertyState srcState,
                                            String parentUUID,
                                            QName propName)
            throws RepositoryException {

        // @todo special handling required for properties with special semantics
        // (e.g. those defined by mix:versionable, mix:lockable, et.al.)
        PropertyState newState = stateMgr.createNew(propName, parentUUID);
        PropDefId defId = srcState.getDefinitionId();
        newState.setDefinitionId(defId);
        newState.setType(srcState.getType());
        newState.setMultiValued(srcState.isMultiValued());
        InternalValue[] values = srcState.getValues();
        if (values != null) {
            InternalValue[] newValues = new InternalValue[values.length];
            for (int i = 0; i < values.length; i++) {
                if (values[i] != null) {
                    newValues[i] = values[i].createCopy();
                } else {
                    newValues[i] = null;
                }
            }
            newState.setValues(values);
            // FIXME delegate to 'node type instance handler'
            if (defId != null) {
                PropDef def = ntReg.getPropDef(defId);
                if (def.getDeclaringNodeType().equals(MIX_REFERENCEABLE)) {
                    if (propName.equals(JCR_UUID)) {
                        // set correct value of jcr:uuid property
                        newState.setValues(new InternalValue[]{InternalValue.create(parentUUID)});
                    }
                }
            }
        }
        return newState;
    }
}
