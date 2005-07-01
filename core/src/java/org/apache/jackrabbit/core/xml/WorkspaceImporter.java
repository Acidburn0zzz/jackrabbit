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
package org.apache.jackrabbit.core.xml;

import org.apache.jackrabbit.core.BatchedItemOperations;
import org.apache.jackrabbit.core.Constants;
import org.apache.jackrabbit.core.HierarchyManager;
import org.apache.jackrabbit.core.MalformedPathException;
import org.apache.jackrabbit.core.NamespaceResolver;
import org.apache.jackrabbit.core.NodeId;
import org.apache.jackrabbit.core.Path;
import org.apache.jackrabbit.core.PropertyId;
import org.apache.jackrabbit.core.QName;
import org.apache.jackrabbit.core.SessionImpl;
import org.apache.jackrabbit.core.WorkspaceImpl;
import org.apache.jackrabbit.core.nodetype.EffectiveNodeType;
import org.apache.jackrabbit.core.nodetype.NodeDef;
import org.apache.jackrabbit.core.nodetype.NodeTypeRegistry;
import org.apache.jackrabbit.core.nodetype.PropDef;
import org.apache.jackrabbit.core.state.NodeState;
import org.apache.jackrabbit.core.state.PropertyState;
import org.apache.jackrabbit.core.state.ItemState;
import org.apache.jackrabbit.core.util.Base64;
import org.apache.jackrabbit.core.util.ReferenceChangeTracker;
import org.apache.jackrabbit.core.util.uuid.UUID;
import org.apache.jackrabbit.core.value.InternalValue;
import org.apache.log4j.Logger;

import javax.jcr.ImportUUIDBehavior;
import javax.jcr.ItemExistsException;
import javax.jcr.ItemNotFoundException;
import javax.jcr.PathNotFoundException;
import javax.jcr.PropertyType;
import javax.jcr.RepositoryException;
import javax.jcr.lock.LockException;
import javax.jcr.nodetype.ConstraintViolationException;
import javax.jcr.version.VersionException;
import javax.jcr.version.VersionHistory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import java.util.Stack;

/**
 * <code>WorkspaceImporter</code> ...
 */
public class WorkspaceImporter implements Importer, Constants {

    private static Logger log = Logger.getLogger(WorkspaceImporter.class);

    private final NodeState importTarget;
    private final WorkspaceImpl wsp;
    private final NodeTypeRegistry ntReg;
    private final HierarchyManager hierMgr;
    private final BatchedItemOperations itemOps;

    private final int uuidBehavior;

    private boolean aborted;
    private Stack parents;

    /**
     * helper object that keeps track of remapped uuid's and imported reference
     * properties that might need correcting depending on the uuid mappings
     */
    private final ReferenceChangeTracker refTracker;

    /**
     * Creates a new <code>WorkspaceImporter</code> instance.
     *
     * @param parentPath   target path where to add the imported subtree
     * @param wsp
     * @param ntReg
     * @param uuidBehavior flag that governs how incoming UUIDs are handled
     * @throws PathNotFoundException        if no node exists at
     *                                      <code>parentPath</code> or if the
     *                                      current session is not granted read
     *                                      access.
     * @throws ConstraintViolationException if the node at
     *                                      <code>parentPath</code> is protected
     * @throws VersionException             if the node at
     *                                      <code>parentPath</code> is not
     *                                      checked-out
     * @throws LockException                if a lock prevents the addition of
     *                                      the subtree
     * @throws RepositoryException          if another error occurs
     */
    public WorkspaceImporter(Path parentPath,
                             WorkspaceImpl wsp,
                             NodeTypeRegistry ntReg,
                             int uuidBehavior)
            throws PathNotFoundException, ConstraintViolationException,
            VersionException, LockException, RepositoryException {

        SessionImpl ses = (SessionImpl) wsp.getSession();
        itemOps = new BatchedItemOperations(wsp.getItemStateManager(),
                ntReg, wsp.getLockManager(), ses, wsp.getHierarchyManager(),
                ses.getNamespaceResolver());
        hierMgr = wsp.getHierarchyManager();

        // perform preliminary checks
        itemOps.verifyCanWrite(parentPath);
        importTarget = itemOps.getNodeState(parentPath);

        this.wsp = wsp;
        this.ntReg = ntReg;
        this.uuidBehavior = uuidBehavior;

        aborted = false;

        refTracker = new ReferenceChangeTracker();

        parents = new Stack();
        parents.push(importTarget);
    }

    /**
     * @param parent
     * @param conflicting
     * @param nodeInfo
     * @return
     * @throws RepositoryException
     */
    protected NodeState resolveUUIDConflict(NodeState parent,
                                            NodeState conflicting,
                                            NodeInfo nodeInfo)
            throws RepositoryException {

        NodeState node;
        if (uuidBehavior == ImportUUIDBehavior.IMPORT_UUID_CREATE_NEW) {
            // create new with new uuid:
            // check if new node can be added (check access rights &
            // node type constraints only, assume locking & versioning status
            // has already been checked on ancestor)
            itemOps.checkAddNode(parent, nodeInfo.getName(),
                    nodeInfo.getNodeTypeName(),
                    BatchedItemOperations.CHECK_ACCESS
                    | BatchedItemOperations.CHECK_CONSTRAINTS);
            node = itemOps.createNodeState(parent, nodeInfo.getName(),
                    nodeInfo.getNodeTypeName(), nodeInfo.getMixinNames(), null);
            // remember uuid mapping
            EffectiveNodeType ent = itemOps.getEffectiveNodeType(node);
            if (ent.includesNodeType(MIX_REFERENCEABLE)) {
                refTracker.mappedUUID(nodeInfo.getUUID(), node.getUUID());
            }
        } else if (uuidBehavior == ImportUUIDBehavior.IMPORT_UUID_COLLISION_THROW) {
            String msg = "a node with uuid " + nodeInfo.getUUID()
                    + " already exists!";
            log.debug(msg);
            throw new ItemExistsException(msg);
        } else if (uuidBehavior == ImportUUIDBehavior.IMPORT_UUID_COLLISION_REMOVE_EXISTING) {
            // make sure conflicting node is not importTarget or an ancestor thereof
            Path p0 = hierMgr.getPath(importTarget.getId());
            Path p1 = hierMgr.getPath(conflicting.getId());
            try {
                if (p1.equals(p0) || p1.isAncestorOf(p0)) {
                    String msg = "cannot remove ancestor node";
                    log.debug(msg);
                    throw new ConstraintViolationException(msg);
                }
            } catch (MalformedPathException mpe) {
                // should never get here...
                String msg = "internal error: failed to determine degree of relationship";
                log.error(msg, mpe);
                throw new RepositoryException(msg, mpe);
            }
            // remove conflicting:
            // check if conflicting can be removed
            // (access rights, node type constraints, locking & versioning status)
            itemOps.checkRemoveNode(conflicting,
                    BatchedItemOperations.CHECK_ACCESS
                    | BatchedItemOperations.CHECK_LOCK
                    | BatchedItemOperations.CHECK_VERSIONING
                    | BatchedItemOperations.CHECK_CONSTRAINTS);
            // do remove conflicting (recursive)
            itemOps.removeNodeState(conflicting);

            // create new with given uuid:
            // check if new node can be added (check access rights &
            // node type constraints only, assume locking & versioning status
            // has already been checked on ancestor)
            itemOps.checkAddNode(parent, nodeInfo.getName(),
                    nodeInfo.getNodeTypeName(),
                    BatchedItemOperations.CHECK_ACCESS
                    | BatchedItemOperations.CHECK_CONSTRAINTS);
            // do create new node
            node = itemOps.createNodeState(parent, nodeInfo.getName(),
                    nodeInfo.getNodeTypeName(), nodeInfo.getMixinNames(),
                    nodeInfo.getUUID());
        } else if (uuidBehavior == ImportUUIDBehavior.IMPORT_UUID_COLLISION_REPLACE_EXISTING) {
            if (conflicting.getParentUUID() == null) {
                String msg = "root node cannot be replaced";
                log.debug(msg);
                throw new RepositoryException(msg);
            }
            // 'replace' current parent with parent of conflicting
            NodeId parentId = new NodeId(conflicting.getParentUUID());
            try {
                parent = itemOps.getNodeState(parentId);
            } catch (ItemNotFoundException infe) {
                // should never get here...
                String msg = "internal error: failed to retrieve parent state";
                log.error(msg, infe);
                throw new RepositoryException(msg, infe);
            }
            // remove conflicting:
            // check if conflicting can be removed
            // (access rights, node type constraints, locking & versioning status)
            itemOps.checkRemoveNode(conflicting,
                    BatchedItemOperations.CHECK_ACCESS
                    | BatchedItemOperations.CHECK_LOCK
                    | BatchedItemOperations.CHECK_VERSIONING
                    | BatchedItemOperations.CHECK_CONSTRAINTS);
            // do remove conflicting (recursive)
            itemOps.removeNodeState(conflicting);
            // create new with given uuid at same location as conflicting:
            // check if new node can be added at other location
            // (access rights, node type constraints, locking & versioning status)
            itemOps.checkAddNode(parent, nodeInfo.getName(),
                    nodeInfo.getNodeTypeName(),
                    BatchedItemOperations.CHECK_ACCESS
                    | BatchedItemOperations.CHECK_LOCK
                    | BatchedItemOperations.CHECK_VERSIONING
                    | BatchedItemOperations.CHECK_CONSTRAINTS);
            // do create new node
            node = itemOps.createNodeState(parent, nodeInfo.getName(),
                    nodeInfo.getNodeTypeName(), nodeInfo.getMixinNames(),
                    nodeInfo.getUUID());
        } else {
            String msg = "unknown uuidBehavior: " + uuidBehavior;
            log.debug(msg);
            throw new RepositoryException(msg);
        }

        return node;
    }

    /**
     * Post-process imported node (initialize properties with special
     * semantics etc.)
     *
     * @param node
     * @throws RepositoryException
     */
    protected void postProcessNode(NodeState node) throws RepositoryException {
        /**
         * special handling required for properties with special semantics
         * (e.g. those defined by mix:referenceable, mix:versionable,
         * mix:lockable, et.al.)
         *
         * todo FIXME delegate to 'node type instance handler'
         */
        EffectiveNodeType ent = itemOps.getEffectiveNodeType(node);
        if (ent.includesNodeType(MIX_VERSIONABLE)) {
            PropDef def;
            PropertyState prop;
            SessionImpl session = (SessionImpl) wsp.getSession();
            VersionHistory hist = session.getVersionManager().createVersionHistory(session, node);

            // jcr:versionHistory
            if (!node.hasPropertyName(JCR_VERSIONHISTORY)) {
                def = itemOps.findApplicablePropertyDefinition(JCR_VERSIONHISTORY,
                        PropertyType.REFERENCE, false, node);
                prop = itemOps.createPropertyState(node, JCR_VERSIONHISTORY,
                        PropertyType.REFERENCE, def);
                prop.setValues(new InternalValue[]{InternalValue.create(new UUID(hist.getUUID()))});
            }

            // jcr:baseVersion
            if (!node.hasPropertyName(JCR_BASEVERSION)) {
                def = itemOps.findApplicablePropertyDefinition(JCR_BASEVERSION,
                        PropertyType.REFERENCE, false, node);
                prop = itemOps.createPropertyState(node, JCR_BASEVERSION,
                        PropertyType.REFERENCE, def);
                prop.setValues(new InternalValue[]{InternalValue.create(new UUID(hist.getRootVersion().getUUID()))});
            }

            // jcr:predecessors
            if (!node.hasPropertyName(JCR_PREDECESSORS)) {
                def = itemOps.findApplicablePropertyDefinition(JCR_PREDECESSORS,
                        PropertyType.REFERENCE, true, node);
                prop = itemOps.createPropertyState(node, JCR_PREDECESSORS,
                        PropertyType.REFERENCE, def);
                prop.setValues(new InternalValue[]{InternalValue.create(new UUID(hist.getRootVersion().getUUID()))});
            }

            // jcr:isCheckedOut
            if (!node.hasPropertyName(JCR_ISCHECKEDOUT)) {
                def = itemOps.findApplicablePropertyDefinition(JCR_ISCHECKEDOUT,
                        PropertyType.BOOLEAN, false, node);
                prop = itemOps.createPropertyState(node, JCR_ISCHECKEDOUT,
                        PropertyType.BOOLEAN, def);
                prop.setValues(new InternalValue[]{InternalValue.create(true)});
            }

        }
    }

    //-------------------------------------------------------------< Importer >
    /**
     * {@inheritDoc}
     */
    public void start() throws RepositoryException {
        try {
            // start update operation
            itemOps.edit();
        } catch (IllegalStateException ise) {
            aborted = true;
            String msg = "internal error: failed to start update operation";
            log.debug(msg);
            throw new RepositoryException(msg, ise);
        }
    }

    /**
     * {@inheritDoc}
     */
    public void startNode(NodeInfo nodeInfo, List propInfos,
                          NamespaceResolver nsContext)
            throws RepositoryException {
        if (aborted) {
            // the import has been aborted, get outta here...
            return;
        }

        boolean succeeded = false;
        NodeState parent;
        try {
            // check sanity of workspace/session first
            wsp.sanityCheck();

            parent = (NodeState) parents.peek();

            // process node

            NodeState node = null;
            String uuid = nodeInfo.getUUID();
            QName nodeName = nodeInfo.getName();
            QName ntName = nodeInfo.getNodeTypeName();
            QName[] mixins = nodeInfo.getMixinNames();

            if (parent == null) {
                // parent node was skipped, skip this child node also
                parents.push(null); // push null onto stack for skipped node
                succeeded = true;
                log.debug("skipping node " + nodeName);
                return;
            }
            if (parent.hasChildNodeEntry(nodeName)) {
                // a node with that name already exists...
                NodeState.ChildNodeEntry entry =
                        parent.getChildNodeEntry(nodeName, 1);
                NodeId idExisting = new NodeId(entry.getUUID());
                NodeState existing = (NodeState) itemOps.getItemState(idExisting);
                NodeDef def = ntReg.getNodeDef(existing.getDefinitionId());

                if (!def.allowsSameNameSiblings()) {
                    // existing doesn't allow same-name siblings,
                    // check for potential conflicts
                    EffectiveNodeType entExisting =
                            itemOps.getEffectiveNodeType(existing);
                    if (def.isProtected() && entExisting.includesNodeType(ntName)) {
                        // skip protected node
                        parents.push(null); // push null onto stack for skipped node
                        succeeded = true;
                        log.debug("skipping protected node "
                                + itemOps.safeGetJCRPath(existing.getId()));
                        return;
                    }
                    if (def.isAutoCreated() && entExisting.includesNodeType(ntName)) {
                        // this node has already been auto-created,
                        // no need to create it
                        node = existing;
                    } else {
                        throw new ItemExistsException(itemOps.safeGetJCRPath(existing.getId()));
                    }
                }
            }

            if (node == null) {
                // there's no node with that name...
                if (uuid == null) {
                    // no potential uuid conflict, always create new node

                    NodeDef def =
                            itemOps.findApplicableNodeDefinition(nodeName, ntName, parent);
                    if (def.isProtected()) {
                        // skip protected node
                        parents.push(null); // push null onto stack for skipped node
                        succeeded = true;
                        log.debug("skipping protected node " + nodeName);
                        return;
                    }

                    if (parent.hasPropertyName(nodeName)) {
                        /**
                         * a property with the same name already exists; if this property
                         * has been imported as well (e.g. through document view import
                         * where an element can have the same name as one of the attributes
                         * of its parent element) we have to rename the onflicting property;
                         *
                         * see http://issues.apache.org/jira/browse/JCR-61
                         */
                        PropertyId propId = new PropertyId(parent.getUUID(), nodeName);
                        PropertyState conflicting = itemOps.getPropertyState(propId);
                        if (conflicting.getStatus() == ItemState.STATUS_NEW) {
                            // assume this property has been imported as well;
                            // rename conflicting property
                            // @todo use better reversible escaping scheme to create unique name
                            QName newName = new QName(nodeName.getNamespaceURI(), nodeName.getLocalName() + "_");
                            if (parent.hasPropertyName(newName)) {
                                newName = new QName(newName.getNamespaceURI(), newName.getLocalName() + "_");
                            }
                            PropertyState newProp =
                                    itemOps.createPropertyState(parent, newName,
                                            conflicting.getType(), conflicting.getValues().length);
                            newProp.setValues(conflicting.getValues());
                            parent.removePropertyName(nodeName);
                            itemOps.store(parent);
                            itemOps.destroy(conflicting);
                        }
                    }

                    // check if new node can be added (check access rights &
                    // node type constraints only, assume locking & versioning status
                    // has already been checked on ancestor)
                    itemOps.checkAddNode(parent, nodeName, ntName,
                            BatchedItemOperations.CHECK_ACCESS
                            | BatchedItemOperations.CHECK_CONSTRAINTS);
                    // do create new node
                    node = itemOps.createNodeState(parent, nodeName, ntName, mixins, null, def);
                } else {
                    // potential uuid conflict
                    NodeState conflicting;

                    try {
                        conflicting = itemOps.getNodeState(new NodeId(uuid));
                    } catch (ItemNotFoundException infe) {
                        conflicting = null;
                    }
                    if (conflicting != null) {
                        // resolve uuid conflict
                        node = resolveUUIDConflict(parent, conflicting, nodeInfo);
                    } else {
                        // create new with given uuid

                        NodeDef def =
                                itemOps.findApplicableNodeDefinition(nodeName, ntName, parent);
                        if (def.isProtected()) {
                            // skip protected node
                            parents.push(null); // push null onto stack for skipped node
                            succeeded = true;
                            log.debug("skipping protected node " + nodeName);
                            return;
                        }

                        // check if new node can be added (check access rights &
                        // node type constraints only, assume locking & versioning status
                        // has already been checked on ancestor)
                        itemOps.checkAddNode(parent, nodeName, ntName,
                                BatchedItemOperations.CHECK_ACCESS
                                | BatchedItemOperations.CHECK_CONSTRAINTS);
                        // do create new node
                        node = itemOps.createNodeState(parent, nodeName, ntName, mixins, uuid, def);
                    }
                }
            }

            // process properties

            Iterator iter = propInfos.iterator();
            while (iter.hasNext()) {
                PropInfo pi = (PropInfo) iter.next();
                QName propName = pi.getName();
                TextValue[] tva = pi.getValues();
                int type = pi.getType();

                PropertyState prop = null;
                PropDef def = null;

                if (node.hasPropertyName(propName)) {
                    // a property with that name already exists...
                    PropertyId idExisting = new PropertyId(node.getUUID(), propName);
                    PropertyState existing =
                            (PropertyState) itemOps.getItemState(idExisting);
                    def = ntReg.getPropDef(existing.getDefinitionId());
                    if (def.isProtected()) {
                        // skip protected property
                        log.debug("skipping protected property "
                                + itemOps.safeGetJCRPath(idExisting));
                        continue;
                    }
                    if (def.isAutoCreated() && (existing.getType() == type
                            || type == PropertyType.UNDEFINED)
                            && def.isMultiple() == existing.isMultiValued()) {
                        // this property has already been auto-created,
                        // no need to create it
                        prop = existing;
                    } else {
                        throw new ItemExistsException(itemOps.safeGetJCRPath(existing.getId()));
                    }
                }
                if (prop == null) {
                    // there's no property with that name,
                    // find applicable definition

                    // multi- or single-valued property?
                    if (tva.length == 1) {
                        // could be single- or multi-valued (n == 1)
                        def = itemOps.findApplicablePropertyDefinition(propName,
                                type, node);
                    } else {
                        // can only be multi-valued (n == 0 || n > 1)
                        def = itemOps.findApplicablePropertyDefinition(propName,
                                type, true, node);
                    }

                    if (def.isProtected()) {
                        // skip protected property
                        log.debug("skipping protected property " + propName);
                        continue;
                    }

                    // create new property
                    prop = itemOps.createPropertyState(node, propName, type, def);
                }

                // check multi-valued characteristic
                if ((tva.length == 0 || tva.length > 1) && !def.isMultiple()) {
                    throw new ConstraintViolationException(itemOps.safeGetJCRPath(prop.getId())
                            + " is not multi-valued");
                }

                // convert serialized values to InternalValue objects
                InternalValue[] iva = new InternalValue[tva.length];
                int targetType = def.getRequiredType();
                if (targetType == PropertyType.UNDEFINED) {
                    if (type == PropertyType.UNDEFINED) {
                        targetType = PropertyType.STRING;
                    } else {
                        targetType = type;
                    }
                }
                for (int i = 0; i < tva.length; i++) {
                    TextValue tv = tva[i];
                    if (targetType == PropertyType.BINARY) {
                        // base64 encoded BINARY type;
                        // decode using Reader
/*
                        // @todo decode to temp file and pass FileInputStream to InternalValue factory method
                        File tmpFile = null;
                        try {
                            tmpFile = File.createTempFile("bin", null);
                            FileOutputStream out = new FileOutputStream(tmpFile);
                            Base64.decode(tv.reader(), out);
                            out.close();
                            iva[i] = InternalValue.create(new FileInputStream(tmpFile));
                        } catch (IOException ioe) {
                            String msg = "failed to decode binary value";
                            log.debug(msg, ioe);
                            throw new RepositoryException(msg, ioe);
                        } finally {
                            // the temp file can be deleted because
                            // the InternalValue instance has spooled
                            // its contents
                            if (tmpFile != null) {
                                tmpFile.delete();
                            }
                        }
*/
                        ByteArrayOutputStream baos = new ByteArrayOutputStream();
                        try {
                            Base64.decode(tv.reader(), baos);
                            // no need to close ByteArrayOutputStream
                            //baos.close();
                            iva[i] = InternalValue.create(new ByteArrayInputStream(baos.toByteArray()));
                        } catch (IOException ioe) {
                            String msg = "failed to decode binary value";
                            log.debug(msg, ioe);
                            throw new RepositoryException(msg, ioe);
                        }
                    } else {
                        // retrieve serialized value
                        String serValue;
                        try {
                            serValue = tv.retrieve();
                        } catch (IOException ioe) {
                            String msg = "failed to retrieve serialized value";
                            log.debug(msg, ioe);
                            throw new RepositoryException(msg, ioe);
                        }

                        // convert serialized value to InternalValue using
                        // current namespace context of xml document
                        iva[i] = InternalValue.create(serValue, targetType,
                                nsContext);
                    }
                }

                // set values
                prop.setValues(iva);

                // make sure property is valid according to its definition
                itemOps.validate(prop);

                if (prop.getType() == PropertyType.REFERENCE) {
                    // store reference for later resolution
                    refTracker.processedReference(prop);
                }

                // store property
                itemOps.store(prop);
            }

            // store affected nodes
            itemOps.store(node);
            itemOps.store(parent);

            // push current node onto stack of parents
            parents.push(node);

            succeeded = true;
        } finally {
            if (!succeeded) {
                // update operation failed, cancel all modifications
                aborted = true;
                itemOps.cancel();
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    public void endNode(NodeInfo nodeInfo) throws RepositoryException {
        if (aborted) {
            // the import has been aborted, get outta here...
            return;
        }
        NodeState node = (NodeState) parents.pop();
        if (node == null) {
            // node was skipped, nothing to do here
            return;
        }
        boolean succeeded = false;
        try {
            // check sanity of workspace/session first
            wsp.sanityCheck();

            // post-process node (initialize properties with special semantics etc.)
            postProcessNode(node);

            // make sure node is valid according to its definition
            itemOps.validate(node);

            // we're done with that node, now store its state
            itemOps.store(node);
            succeeded = true;
        } finally {
            if (!succeeded) {
                // update operation failed, cancel all modifications
                aborted = true;
                itemOps.cancel();
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    public void end() throws RepositoryException {
        if (aborted) {
            // the import has been aborted, get outta here...
            return;
        }

        boolean succeeded = false;
        try {
            // check sanity of workspace/session first
            wsp.sanityCheck();

            /**
             * adjust references that refer to uuid's which have been mapped to
             * newly gererated uuid's on import
             */
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
                    itemOps.store(prop);
                }
            }
            refTracker.clear();

            // make sure import target is valid according to its definition
            itemOps.validate(importTarget);

            // finally store the state of the import target
            // (the parent of the imported subtree)
            itemOps.store(importTarget);
            succeeded = true;
        } finally {
            if (!succeeded) {
                // update operation failed, cancel all modifications
                aborted = true;
                itemOps.cancel();
            }
        }

        if (!aborted) {
            // finish update
            itemOps.update();
        }
    }
}
