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
import org.apache.jackrabbit.core.nodetype.NodeDefId;
import org.apache.jackrabbit.core.nodetype.NodeDefinitionImpl;
import org.apache.jackrabbit.core.nodetype.NodeTypeConflictException;
import org.apache.jackrabbit.core.nodetype.NodeTypeImpl;
import org.apache.jackrabbit.core.nodetype.NodeTypeManagerImpl;
import org.apache.jackrabbit.core.nodetype.NodeTypeRegistry;
import org.apache.jackrabbit.core.nodetype.PropDef;
import org.apache.jackrabbit.core.nodetype.PropertyDefinitionImpl;
import org.apache.jackrabbit.core.state.ItemState;
import org.apache.jackrabbit.core.state.ItemStateException;
import org.apache.jackrabbit.core.state.NodeReferences;
import org.apache.jackrabbit.core.state.NodeReferencesId;
import org.apache.jackrabbit.core.state.NodeState;
import org.apache.jackrabbit.core.state.PropertyState;
import org.apache.jackrabbit.core.util.ChildrenCollectorFilter;
import org.apache.jackrabbit.core.util.IteratorHelper;
import org.apache.jackrabbit.core.util.ValueHelper;
import org.apache.jackrabbit.core.util.uuid.UUID;
import org.apache.jackrabbit.core.version.GenericVersionSelector;
import org.apache.jackrabbit.core.version.InternalFreeze;
import org.apache.jackrabbit.core.version.InternalFrozenNode;
import org.apache.jackrabbit.core.version.InternalFrozenVersionHistory;
import org.apache.jackrabbit.core.version.InternalVersion;
import org.apache.jackrabbit.core.version.VersionHistoryImpl;
import org.apache.jackrabbit.core.version.VersionImpl;
import org.apache.jackrabbit.core.version.VersionSelector;
import org.apache.log4j.Logger;

import javax.jcr.AccessDeniedException;
import javax.jcr.InvalidItemStateException;
import javax.jcr.Item;
import javax.jcr.ItemExistsException;
import javax.jcr.ItemNotFoundException;
import javax.jcr.ItemVisitor;
import javax.jcr.MergeException;
import javax.jcr.NoSuchWorkspaceException;
import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.PathNotFoundException;
import javax.jcr.Property;
import javax.jcr.PropertyIterator;
import javax.jcr.PropertyType;
import javax.jcr.RepositoryException;
import javax.jcr.UnsupportedRepositoryOperationException;
import javax.jcr.Value;
import javax.jcr.ValueFormatException;
import javax.jcr.lock.Lock;
import javax.jcr.lock.LockException;
import javax.jcr.nodetype.ConstraintViolationException;
import javax.jcr.nodetype.NoSuchNodeTypeException;
import javax.jcr.nodetype.NodeDefinition;
import javax.jcr.nodetype.NodeType;
import javax.jcr.nodetype.PropertyDefinition;
import javax.jcr.version.OnParentVersionAction;
import javax.jcr.version.Version;
import javax.jcr.version.VersionException;
import javax.jcr.version.VersionHistory;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * <code>NodeImpl</code> implements the <code>Node</code> interface.
 */
public class NodeImpl extends ItemImpl implements Node {

    private static Logger log = Logger.getLogger(NodeImpl.class);

    protected final NodeTypeImpl nodeType;

    protected NodeDefinition definition;

    // flag set in status passed to getOrCreateProperty if property was created
    protected static final short CREATED = 0;

    /**
     * Protected constructor.
     *
     * @param itemMgr    the <code>ItemManager</code> that created this <code>Node</code> instance
     * @param session    the <code>Session</code> through which this <code>Node</code> is acquired
     * @param id         id of this <code>Node</code>
     * @param state      state associated with this <code>Node</code>
     * @param definition definition of <i>this</i> <code>Node</code>
     * @param listeners  listeners on life cylce changes of this <code>NodeImpl</code>
     * @throws RepositoryException
     */
    protected NodeImpl(ItemManager itemMgr, SessionImpl session, NodeId id,
                       NodeState state, NodeDefinition definition,
                       ItemLifeCycleListener[] listeners)
            throws RepositoryException {
        super(itemMgr, session, id, state, listeners);
        nodeType = session.getNodeTypeManager().getNodeType(state.getNodeTypeName());
        this.definition = definition;
    }

    /**
     * Returns the id of the property at <code>relPath</code> or <code>null</code>
     * if no property exists at <code>relPath</code>.
     * <p/>
     * Note that access rights are not checked.
     *
     * @param relPath relative path of a (possible) property
     * @return the id of the property at <code>relPath</code> or
     *         <code>null</code> if no property exists at <code>relPath</code>
     * @throws RepositoryException if <code>relPath</code> is not a valid
     *                             relative path
     */
    protected PropertyId resolveRelativePropertyPath(String relPath)
            throws RepositoryException {
        try {
            /**
             * first check if relPath is just a name (in which case we don't
             * have to build & resolve absolute path)
             */
            Path p = Path.create(relPath, session.getNamespaceResolver(), false);
            if (p.getLength() == 1) {
                Path.PathElement pe = p.getNameElement();
                if (pe.denotesName()) {
                    if (pe.getIndex() > 0) {
                        // property name can't have subscript
                        String msg = relPath + " is not a valid property path";
                        log.debug(msg);
                        throw new RepositoryException(msg);
                    }
                    // check if property entry exists
                    NodeState thisState = (NodeState) state;
                    if (thisState.hasPropertyEntry(pe.getName())) {
                        return new PropertyId(thisState.getUUID(), pe.getName());
                    } else {
                        // there's no property with that name
                        return null;
                    }
                }
            }
            /**
             * build and resolve absolute path
             */
            p = Path.create(getPrimaryPath(), relPath, session.getNamespaceResolver(), true);
            try {
                ItemId id = session.getHierarchyManager().resolvePath(p);
                if (!id.denotesNode()) {
                    return (PropertyId) id;
                } else {
                    // not a property
                    return null;
                }
            } catch (PathNotFoundException pnfe) {
                return null;
            }
        } catch (MalformedPathException e) {
            String msg = "failed to resolve path " + relPath + " relative to " + safeGetJCRPath();
            log.debug(msg);
            throw new RepositoryException(msg, e);
        }
    }

    /**
     * Returns the id of the node at <code>relPath</code> or <code>null</code>
     * if no node exists at <code>relPath</code>.
     * <p/>
     * Note that access rights are not checked.
     *
     * @param relPath relative path of a (possible) node
     * @return the id of the node at <code>relPath</code> or
     *         <code>null</code> if no node exists at <code>relPath</code>
     * @throws RepositoryException if <code>relPath</code> is not a valid
     *                             relative path
     */
    protected NodeId resolveRelativeNodePath(String relPath)
            throws RepositoryException {
        try {
            /**
             * first check if relPath is just a name (in which case we don't
             * have to build & resolve absolute path)
             */
            Path p = Path.create(relPath, session.getNamespaceResolver(), false);
            if (p.getLength() == 1) {
                Path.PathElement pe = p.getNameElement();
                if (pe.denotesName()) {
                    // check if node entry exists
                    NodeState thisState = (NodeState) state;
                    int index = pe.getIndex();
                    if (index == 0) {
                        index = 1;
                    }
                    NodeState.ChildNodeEntry cne =
                            thisState.getChildNodeEntry(pe.getName(), index);
                    if (cne != null) {
                        return new NodeId(cne.getUUID());
                    } else {
                        // there's no child node with that name
                        return null;
                    }
                }
            }
            /**
             * build and resolve absolute path
             */
            p = Path.create(getPrimaryPath(), relPath, session.getNamespaceResolver(), true);
            try {
                ItemId id = session.getHierarchyManager().resolvePath(p);
                if (id.denotesNode()) {
                    return (NodeId) id;
                } else {
                    // not a node
                    return null;
                }
            } catch (PathNotFoundException pnfe) {
                return null;
            }
        } catch (MalformedPathException e) {
            String msg = "failed to resolve path " + relPath + " relative to " + safeGetJCRPath();
            log.debug(msg);
            throw new RepositoryException(msg, e);
        }
    }

    /**
     * Determines if there are pending unsaved changes either on <i>this</i>
     * node or on any node or property in the subtree below it.
     *
     * @return <code>true</code> if there are pending unsaved changes,
     *         <code>false</code> otherwise.
     * @throws RepositoryException if an error occured
     */
    protected boolean hasPendingChanges() throws RepositoryException {
        if (isTransient()) {
            return true;
        }
        Iterator iter = stateMgr.getDescendantTransientItemStates(id);
        return iter.hasNext();
    }

    protected synchronized ItemState getOrCreateTransientItemState()
            throws RepositoryException {
        if (!isTransient()) {
            try {
                // make transient (copy-on-write)
                NodeState transientState =
                        stateMgr.createTransientNodeState((NodeState) state, ItemState.STATUS_EXISTING_MODIFIED);
                // remove listener on persistent state
                state.removeListener(this);
                // add listener on transient state
                transientState.addListener(this);
                // replace persistent with transient state
                state = transientState;
            } catch (ItemStateException ise) {
                String msg = "failed to create transient state";
                log.debug(msg);
                throw new RepositoryException(msg, ise);
            }
        }
        return state;
    }

    /**
     * Computes the values of well-known system (i.e. protected) properties.
     * todo: duplicate code in WorkspaceImporter: consolidate and delegate to NodeTypeInstanceHandler
     *
     * @param name
     * @param def
     * @return
     * @throws RepositoryException
     */
    protected InternalValue[] computeSystemGeneratedPropertyValues(QName name,
                                                                   PropertyDefinitionImpl def)
            throws RepositoryException {
        InternalValue[] genValues = null;

        /**
         * todo: need to come up with some callback mechanism for applying system generated values
         * (e.g. using a NodeTypeInstanceHandler interface)
         */

        NodeState thisState = (NodeState) state;

        // compute system generated values
        NodeTypeImpl nt = (NodeTypeImpl) def.getDeclaringNodeType();
        if (nt.getQName().equals(MIX_REFERENCEABLE)) {
            // mix:referenceable node type
            if (name.equals(JCR_UUID)) {
                // jcr:uuid property
                genValues = new InternalValue[]{InternalValue.create(thisState.getUUID())};
            }
/*
	todo consolidate version history creation code (currently in ItemImpl.initVersionHistories)
	} else if (nt.getQName().equals(MIX_VERSIONABLE)) {
	    // mix:versionable node type
	    VersionHistory hist = session.getVersionManager().getOrCreateVersionHistory(this);
	    if (name.equals(JCR_VERSIONHISTORY)) {
		// jcr:versionHistory property
		genValues = new InternalValue[]{InternalValue.create(new UUID(hist.getUUID()))};
	    } else if (name.equals(JCR_BASEVERSION)) {
		// jcr:baseVersion property
		genValues = new InternalValue[]{InternalValue.create(new UUID(hist.getRootVersion().getUUID()))};
	    } else if (name.equals(JCR_ISCHECKEDOUT)) {
		// jcr:isCheckedOut property
		genValues = new InternalValue[]{InternalValue.create(true)};
	    } else if (name.equals(JCR_PREDECESSORS)) {
		// jcr:predecessors property
		genValues = new InternalValue[]{InternalValue.create(new UUID(hist.getRootVersion().getUUID()))};
	    }
*/
        } else if (nt.getQName().equals(NT_HIERARCHYNODE)) {
            // nt:hierarchyNode node type
            if (name.equals(JCR_CREATED)) {
                // jcr:created property
                genValues = new InternalValue[]{InternalValue.create(Calendar.getInstance())};
            }
        } else if (nt.getQName().equals(NT_RESOURCE)) {
            // nt:resource node type
            if (name.equals(JCR_LASTMODIFIED)) {
                // jcr:lastModified property
                genValues = new InternalValue[]{InternalValue.create(Calendar.getInstance())};
            }
        } else if (nt.getQName().equals(NT_VERSION)) {
            // nt:version node type
            if (name.equals(JCR_CREATED)) {
                // jcr:created property
                genValues = new InternalValue[]{InternalValue.create(Calendar.getInstance())};
            }
        } else if (nt.getQName().equals(NT_BASE)) {
            // nt:base node type
            if (name.equals(JCR_PRIMARYTYPE)) {
                // jcr:primaryType property
                genValues = new InternalValue[]{InternalValue.create(nodeType.getQName())};
            } else if (name.equals(JCR_MIXINTYPES)) {
                // jcr:mixinTypes property
                Set mixins = thisState.getMixinTypeNames();
                ArrayList values = new ArrayList(mixins.size());
                Iterator iter = mixins.iterator();
                while (iter.hasNext()) {
                    values.add(InternalValue.create((QName) iter.next()));
                }
                genValues = (InternalValue[]) values.toArray(new InternalValue[values.size()]);
            }
        }

        return genValues;
    }

    /**
     * @param name
     * @param type
     * @param multiValued
     * @param status
     * @return
     * @throws ConstraintViolationException if no applicable property definition
     *                                      could be found
     * @throws RepositoryException          if another error occurs
     */
    protected PropertyImpl getOrCreateProperty(String name, int type,
                                               boolean multiValued,
                                               BitSet status)
            throws ConstraintViolationException, RepositoryException {
        QName qName;
        try {
            qName = QName.fromJCRName(name, session.getNamespaceResolver());
        } catch (IllegalNameException ine) {
            throw new RepositoryException("invalid property name: " + name, ine);
        } catch (UnknownPrefixException upe) {
            throw new RepositoryException("invalid property name: " + name, upe);
        }
        return getOrCreateProperty(qName, type, multiValued, status);
    }

    /**
     * @param name
     * @param type
     * @param multiValued
     * @param status
     * @return
     * @throws ConstraintViolationException if no applicable property definition
     *                                      could be found
     * @throws RepositoryException          if another error occurs
     */
    protected synchronized PropertyImpl getOrCreateProperty(QName name, int type,
                                                            boolean multiValued,
                                                            BitSet status)
            throws ConstraintViolationException, RepositoryException {
        status.clear();

        NodeState thisState = (NodeState) state;
        if (thisState.hasPropertyEntry(name)) {
            /**
             * the following call will throw ItemNotFoundException if the
             * current session doesn't have read access
             */
            return getProperty(name);
        }

        // does not exist yet:
        // find definition for the specified property and create property
        PropertyDefinitionImpl def = getApplicablePropertyDefinition(name, type, multiValued);
        PropertyImpl prop = createChildProperty(name, type, def);
        status.set(CREATED);
        return prop;
    }

    protected synchronized PropertyImpl createChildProperty(QName name, int type,
                                                            PropertyDefinitionImpl def)
            throws RepositoryException {
        // check for name collisions with existing child nodes
        if (((NodeState) state).hasChildNodeEntry(name)) {
            String msg = "there's already a child node with name " + name;
            log.debug(msg);
            throw new RepositoryException(msg);
        }

        String parentUUID = ((NodeState) state).getUUID();

        // create a new property state
        PropertyState propState;
        try {
            propState =
                    stateMgr.createTransientPropertyState(parentUUID, name,
                            ItemState.STATUS_NEW);
            propState.setType(type);
            propState.setMultiValued(def.isMultiple());
            propState.setDefinitionId(def.unwrap().getId());
            // compute system generated values if necessary
            InternalValue[] genValues =
                    computeSystemGeneratedPropertyValues(name, def);
            InternalValue[] defValues = def.unwrap().getDefaultValues();
            if (genValues != null) {
                propState.setValues(genValues);
            } else if (defValues != null) {
                propState.setValues(defValues);
            }
        } catch (ItemStateException ise) {
            String msg = "failed to add property " + name + " to "
                    + safeGetJCRPath();
            log.debug(msg);
            throw new RepositoryException(msg, ise);
        }

        // create Property instance wrapping new property state
        PropertyImpl prop = itemMgr.createPropertyInstance(propState, def);

        // modify the state of 'this', i.e. the parent node
        NodeState thisState = (NodeState) getOrCreateTransientItemState();
        // add new property entry
        thisState.addPropertyEntry(name);

        return prop;
    }

    protected synchronized NodeImpl createChildNode(QName name,
                                                    NodeDefinitionImpl def,
                                                    NodeTypeImpl nodeType,
                                                    String uuid)
            throws RepositoryException {
        String parentUUID = ((NodeState) state).getUUID();
        // create a new node state
        NodeState nodeState;
        try {
            if (uuid == null) {
                uuid = UUID.randomUUID().toString();	// version 4 uuid
            }
            nodeState =
                    stateMgr.createTransientNodeState(uuid, nodeType.getQName(),
                            parentUUID, ItemState.STATUS_NEW);
            nodeState.setDefinitionId(def.unwrap().getId());
        } catch (ItemStateException ise) {
            String msg = "failed to add child node " + name + " to "
                    + safeGetJCRPath();
            log.debug(msg);
            throw new RepositoryException(msg, ise);
        }

        // create Node instance wrapping new node state
        NodeImpl node;
        try {
            node = itemMgr.createNodeInstance(nodeState, def);
        } catch (RepositoryException re) {
            // something went wrong
            stateMgr.disposeTransientItemState(nodeState);
            // re-throw
            throw re;
        }

        // modify the state of 'this', i.e. the parent node
        NodeState thisState = (NodeState) getOrCreateTransientItemState();
        // add new child node entry
        thisState.addChildNodeEntry(name, nodeState.getUUID());

        // add 'auto-create' properties defined in node type
        PropertyDefinition[] pda = nodeType.getAutoCreatedPropertyDefinitions();
        for (int i = 0; i < pda.length; i++) {
            PropertyDefinitionImpl pd = (PropertyDefinitionImpl) pda[i];
            node.createChildProperty(pd.getQName(), pd.getRequiredType(), pd);
        }

        // recursively add 'auto-create' child nodes defined in node type
        NodeDefinition[] nda = nodeType.getAutoCreatedNodeDefinitions();
        for (int i = 0; i < nda.length; i++) {
            NodeDefinitionImpl nd = (NodeDefinitionImpl) nda[i];
            node.createChildNode(nd.getQName(), nd,
                    (NodeTypeImpl) nd.getDefaultPrimaryType(), null);
        }

        return node;
    }

    protected NodeImpl createChildNodeLink(QName nodeName, String targetUUID)
            throws RepositoryException {
        // modify the state of 'this', i.e. the parent node
        NodeState thisState = (NodeState) getOrCreateTransientItemState();

        NodeId targetId = new NodeId(targetUUID);
        NodeImpl targetNode = (NodeImpl) itemMgr.getItem(targetId);
        // notify target of link
        targetNode.onLink(thisState);

        thisState.addChildNodeEntry(nodeName, targetUUID);

        return (NodeImpl) itemMgr.getItem(targetId);
    }


    protected void removeChildProperty(String propName) throws RepositoryException {
        QName qName;
        try {
            qName = QName.fromJCRName(propName, session.getNamespaceResolver());
        } catch (IllegalNameException ine) {
            throw new RepositoryException("invalid property name: "
                    + propName, ine);
        } catch (UnknownPrefixException upe) {
            throw new RepositoryException("invalid property name: "
                    + propName, upe);
        }
        removeChildProperty(qName);
    }

    protected void removeChildProperty(QName propName) throws RepositoryException {
        // modify the state of 'this', i.e. the parent node
        NodeState thisState = (NodeState) getOrCreateTransientItemState();

        // remove the property entry
        if (!thisState.removePropertyEntry(propName)) {
            String msg = "failed to remove property " + propName + " of "
                    + safeGetJCRPath();
            log.debug(msg);
            throw new RepositoryException(msg);
        }

        // remove property
        PropertyId propId = new PropertyId(thisState.getUUID(), propName);
        itemMgr.getItem(propId).setRemoved();
    }

    protected void removeChildNode(QName nodeName, int index)
            throws RepositoryException {
        // modify the state of 'this', i.e. the parent node
        NodeState thisState = (NodeState) getOrCreateTransientItemState();
        if (index == 0) {
            index = 1;
        }
        NodeState.ChildNodeEntry entry =
                thisState.getChildNodeEntry(nodeName, index);
        if (entry == null) {
            String msg = "failed to remove child " + nodeName + " of "
                    + safeGetJCRPath();
            log.debug(msg);
            throw new RepositoryException(msg);
        }

        NodeId childId = new NodeId(entry.getUUID());
        NodeImpl childNode = (NodeImpl) itemMgr.getItem(childId);
        // notify target of removal/unlink
        childNode.onUnlink(thisState);

        // remove child entry
        if (!thisState.removeChildNodeEntry(nodeName, index)) {
            String msg = "failed to remove child " + nodeName + " of "
                    + safeGetJCRPath();
            log.debug(msg);
            throw new RepositoryException(msg);
        }
    }

    protected void onRedefine(NodeDefId defId) throws RepositoryException {
        NodeDefinitionImpl newDef =
                session.getNodeTypeManager().getNodeDefinition(defId);
        // modify the state of 'this', i.e. the target node
        NodeState thisState = (NodeState) getOrCreateTransientItemState();
        // set id of new definition
        thisState.setDefinitionId(defId);
        definition = newDef;
    }

    protected void onLink(NodeState parentState) throws RepositoryException {
        // modify the state of 'this', i.e. the target node
        NodeState thisState = (NodeState) getOrCreateTransientItemState();
        // add uuid of this node to target's parent list
        thisState.addParentUUID(parentState.getUUID());
    }

    protected void onUnlink(NodeState parentState) throws RepositoryException {
        // modify the state of 'this', i.e. the target node
        NodeState thisState = (NodeState) getOrCreateTransientItemState();

        // check if this node would be orphaned after unlinking it from parent
        ArrayList parentUUIDs = new ArrayList(thisState.getParentUUIDs());
        parentUUIDs.remove(parentState.getUUID());
        boolean orphaned = parentUUIDs.isEmpty();

        if (orphaned) {
            // remove child nodes (recursive)
            // use temp array to avoid ConcurrentModificationException
            ArrayList tmp = new ArrayList(thisState.getChildNodeEntries());
            // remove from tail to avoid problems with same-name siblings
            for (int i = tmp.size() - 1; i >= 0; i--) {
                NodeState.ChildNodeEntry entry =
                        (NodeState.ChildNodeEntry) tmp.get(i);
                removeChildNode(entry.getName(), entry.getIndex());
            }

            // remove properties
            // use temp array to avoid ConcurrentModificationException
            tmp = new ArrayList(thisState.getPropertyEntries());
            for (int i = 0; i < tmp.size(); i++) {
                NodeState.PropertyEntry entry =
                        (NodeState.PropertyEntry) tmp.get(i);
                removeChildProperty(entry.getName());
            }
        }

        // now actually do unlink this node from specified parent node
        // (i.e. remove uuid of parent node from this node's parent list)
        thisState.removeParentUUID(parentState.getUUID());

        if (orphaned) {
            // remove this node
            itemMgr.getItem(id).setRemoved();
        }
    }

    protected NodeImpl internalAddNode(String relPath, NodeTypeImpl nodeType)
            throws ItemExistsException, PathNotFoundException, VersionException,
            ConstraintViolationException, LockException, RepositoryException {
        return internalAddNode(relPath, nodeType, null);
    }

    protected NodeImpl internalAddNode(String relPath, NodeTypeImpl nodeType,
                                       String uuid)
            throws ItemExistsException, PathNotFoundException, VersionException,
            ConstraintViolationException, LockException, RepositoryException {
        Path nodePath;
        QName nodeName;
        Path parentPath;
        try {
            nodePath =
                    Path.create(getPrimaryPath(), relPath,
                            session.getNamespaceResolver(), true);
            if (nodePath.getNameElement().getIndex() != 0) {
                String msg = "illegal subscript specified: " + nodePath;
                log.debug(msg);
                throw new RepositoryException(msg);
            }
            nodeName = nodePath.getNameElement().getName();
            parentPath = nodePath.getAncestor(1);
        } catch (MalformedPathException e) {
            String msg = "failed to resolve path " + relPath + " relative to "
                    + safeGetJCRPath();
            log.debug(msg);
            throw new RepositoryException(msg, e);
        }

        NodeImpl parentNode;
        try {
            Item parent = itemMgr.getItem(parentPath);
            if (!parent.isNode()) {
                String msg = "cannot add a node to property " + parentPath;
                log.debug(msg);
                throw new ConstraintViolationException(msg);
            }
            parentNode = (NodeImpl) parent;
        } catch (AccessDeniedException ade) {
            throw new PathNotFoundException(relPath);
        }

        // make sure that parent node is checked-out
        if (!parentNode.internalIsCheckedOut()) {
            String msg = safeGetJCRPath()
                    + ": cannot add a child to a checked-in node";
            log.debug(msg);
            throw new VersionException(msg);
        }

        // check lock status
        parentNode.checkLock();

        // delegate the creation of the child node to the parent node
        return parentNode.internalAddChildNode(nodeName, nodeType, uuid);
    }

    protected NodeImpl internalAddChildNode(QName nodeName,
                                            NodeTypeImpl nodeType)
            throws ItemExistsException, ConstraintViolationException,
            RepositoryException {
        return internalAddChildNode(nodeName, nodeType, null);
    }

    protected NodeImpl internalAddChildNode(QName nodeName,
                                            NodeTypeImpl nodeType, String uuid)
            throws ItemExistsException, ConstraintViolationException,
            RepositoryException {
        Path nodePath;
        try {
            nodePath = Path.create(getPrimaryPath(), nodeName, true);
        } catch (MalformedPathException e) {
            // should never happen
            String msg = "internal error: invalid path " + safeGetJCRPath();
            log.debug(msg);
            throw new RepositoryException(msg, e);
        }

        NodeDefinitionImpl def;
        try {
            QName nodeTypeName = null;
            if (nodeType != null) {
                nodeTypeName = nodeType.getQName();
            }
            def = getApplicableChildNodeDefinition(nodeName, nodeTypeName);
        } catch (RepositoryException re) {
            String msg = "no definition found in parent node's node type for new node";
            log.debug(msg);
            throw new ConstraintViolationException(msg, re);
        }
        if (nodeType == null) {
            // use default node type
            nodeType = (NodeTypeImpl) def.getDefaultPrimaryType();
        }

        // check for name collisions
        NodeState thisState = (NodeState) state;
        if (thisState.hasPropertyEntry(nodeName)) {
            // there's already a property with that name
            throw new ItemExistsException(itemMgr.safeGetJCRPath(nodePath));
        }
        NodeState.ChildNodeEntry cne = thisState.getChildNodeEntry(nodeName, 1);
        if (cne != null) {
            // there's already a child node entry with that name;
            // check same-name sibling setting of new node
            if (!def.allowsSameNameSiblings()) {
                throw new ItemExistsException(itemMgr.safeGetJCRPath(nodePath));
            }
            // check same-name sibling setting of existing node
            NodeId newId = new NodeId(cne.getUUID());
            if (!((NodeImpl) itemMgr.getItem(newId)).getDefinition().allowsSameNameSiblings()) {
                throw new ItemExistsException(itemMgr.safeGetJCRPath(nodePath));
            }
        }

        // check protected flag of parent (i.e. this) node
        if (definition.isProtected()) {
            String msg = safeGetJCRPath() + ": cannot add a child to a protected node";
            log.debug(msg);
            throw new ConstraintViolationException(msg);
        }

        // now do create the child node
        return createChildNode(nodeName, def, nodeType, uuid);
    }

    private void setMixinTypesProperty(Set mixinNames) throws RepositoryException {
        NodeState thisState = (NodeState) state;
        // get or create jcr:mixinTypes property
        PropertyImpl prop;
        if (thisState.hasPropertyEntry(JCR_MIXINTYPES)) {
            prop = (PropertyImpl) itemMgr.getItem(new PropertyId(thisState.getUUID(), JCR_MIXINTYPES));
        } else {
            // find definition for the jcr:mixinTypes property and create property
            PropertyDefinitionImpl def = getApplicablePropertyDefinition(JCR_MIXINTYPES, PropertyType.NAME, true);
            prop = createChildProperty(JCR_MIXINTYPES, PropertyType.NAME, def);
        }

        if (mixinNames.isEmpty()) {
            // purge empty jcr:mixinTypes property
            removeChildProperty(JCR_MIXINTYPES);
            return;
        }

        // call internalSetValue for setting the jcr:mixinTypes property
        // to avoid checking of the 'protected' flag
        InternalValue[] vals = new InternalValue[mixinNames.size()];
        Iterator iter = mixinNames.iterator();
        int cnt = 0;
        while (iter.hasNext()) {
            vals[cnt++] = InternalValue.create((QName) iter.next());
        }
        prop.internalSetValue(vals, PropertyType.NAME);
    }

    /**
     * Returns the effective (i.e. merged and resolved) node type representation
     * of this node's primary and mixin node types.
     *
     * @return the effective node type
     * @throws RepositoryException
     */
    public EffectiveNodeType getEffectiveNodeType() throws RepositoryException {
        // build effective node type of mixins & primary type
        NodeTypeRegistry ntReg = session.getNodeTypeManager().getNodeTypeRegistry();
        // existing mixin's
        HashSet set = new HashSet(((NodeState) state).getMixinTypeNames());
        // primary type
        set.add(nodeType.getQName());
        try {
            return ntReg.getEffectiveNodeType((QName[]) set.toArray(new QName[set.size()]));
        } catch (NodeTypeConflictException ntce) {
            String msg = "internal error: failed to build effective node type for node " + safeGetJCRPath();
            log.debug(msg);
            throw new RepositoryException(msg, ntce);
        }
    }

    /**
     * Returns the applicable child node definition for a child node with the
     * specified name and node type.
     *
     * @param nodeName
     * @param nodeTypeName
     * @return
     * @throws ConstraintViolationException if no applicable child node definition
     *                                      could be found
     * @throws RepositoryException          if another error occurs
     */
    protected NodeDefinitionImpl getApplicableChildNodeDefinition(QName nodeName,
                                                                  QName nodeTypeName)
            throws ConstraintViolationException, RepositoryException {
        NodeDef cnd = getEffectiveNodeType().getApplicableChildNodeDef(nodeName, nodeTypeName);
        return session.getNodeTypeManager().getNodeDefinition(cnd.getId());
    }

    /**
     * Returns the applicable property definition for a property with the
     * specified name and type.
     *
     * @param propertyName
     * @param type
     * @param multiValued
     * @return
     * @throws ConstraintViolationException if no applicable property definition
     *                                      could be found
     * @throws RepositoryException          if another error occurs
     */
    protected PropertyDefinitionImpl getApplicablePropertyDefinition(QName propertyName,
                                                                     int type,
                                                                     boolean multiValued)
            throws ConstraintViolationException, RepositoryException {
        PropDef pd = getEffectiveNodeType().getApplicablePropertyDef(propertyName, type, multiValued);
        return session.getNodeTypeManager().getPropertyDefinition(pd.getId());
    }

    protected void makePersistent() {
        if (!isTransient()) {
            log.debug(safeGetJCRPath() + " (" + id + "): there's no transient state to persist");
            return;
        }

        NodeState transientState = (NodeState) state;

        NodeState persistentState = (NodeState) transientState.getOverlayedState();
        if (persistentState == null) {
            // this node is 'new'
            persistentState = stateMgr.createNew(transientState.getUUID(),
                    transientState.getNodeTypeName(), transientState.getParentUUID());
        }
        // copy state from transient state:
        // parent uuid's
        persistentState.setParentUUID(transientState.getParentUUID());
        persistentState.setParentUUIDs(transientState.getParentUUIDs());
        // mixin types
        persistentState.setMixinTypeNames(transientState.getMixinTypeNames());
        // id of definition
        persistentState.setDefinitionId(transientState.getDefinitionId());
        // child node entries
        persistentState.setChildNodeEntries(transientState.getChildNodeEntries());
        // property entries
        persistentState.setPropertyEntries(transientState.getPropertyEntries());

        // make state persistent
        stateMgr.store(persistentState);
        // remove listener from transient state
        transientState.removeListener(this);
        // add listener to persistent state
        persistentState.addListener(this);
        // swap transient state with persistent state
        state = persistentState;
        // reset status
        status = STATUS_NORMAL;
    }

    /**
     * Same as <code>{@link Node#getReferences()}</code> except that
     * this method also filters out the references that appear to be non-existent
     * in this workspace if <code>skipInexistent</code> is set to <code>true</code>.
     *
     * @param skipInexistent if set to <code>true</code> inexistent items are skipped
     */
    protected PropertyIterator getReferences(boolean skipInexistent)
            throws RepositoryException {
        try {
            NodeReferencesId targetId = new NodeReferencesId(((NodeId) id).getUUID());
            NodeReferences refs = getOrCreateNodeReferences(targetId);
            // refs.getReferences returns a list of PropertyId's
            List idList = refs.getReferences();
            return new LazyItemIterator(itemMgr, idList, skipInexistent);
        } catch (ItemStateException e) {
            String msg = "Unable to retrieve node references for: " + id;
            log.debug(msg);
            throw new RepositoryException(msg, e);
        }
    }

    /**
     * Same as {@link Node#addMixin(String)}, but takes a <code>QName</code>
     * instad of a <code>String</code>.
     *
     * @see Node#addMixin(String)
     */
    public void addMixin(QName mixinName)
            throws NoSuchNodeTypeException, VersionException,
            ConstraintViolationException, LockException, RepositoryException {
        // check state of this instance
        sanityCheck();

        // make sure this node is checked-out
        if (!internalIsCheckedOut()) {
            String msg = safeGetJCRPath() + ": cannot add a mixin node type to a checked-in node";
            log.debug(msg);
            throw new VersionException(msg);
        }

        // check protected flag
        if (definition.isProtected()) {
            String msg = safeGetJCRPath() + ": cannot add a mixin node type to a protected node";
            log.debug(msg);
            throw new ConstraintViolationException(msg);
        }

        // check lock status
        checkLock();

        NodeTypeManagerImpl ntMgr = session.getNodeTypeManager();
        NodeTypeImpl mixin = ntMgr.getNodeType(mixinName);
        if (!mixin.isMixin()) {
            throw new RepositoryException(mixinName + ": not a mixin node type");
        }
        if (nodeType.isDerivedFrom(mixinName)) {
            throw new RepositoryException(mixinName + ": already contained in primary node type");
        }

        // build effective node type of mixin's & primary type in order to detect conflicts
        NodeTypeRegistry ntReg = ntMgr.getNodeTypeRegistry();
        EffectiveNodeType entExisting;
        try {
            // existing mixin's
            HashSet set = new HashSet(((NodeState) state).getMixinTypeNames());
            // primary type
            set.add(nodeType.getQName());
            // build effective node type representing primary type including existing mixin's
            entExisting = ntReg.getEffectiveNodeType((QName[]) set.toArray(new QName[set.size()]));
            if (entExisting.includesNodeType(mixinName)) {
                throw new RepositoryException(mixinName + ": already contained in mixin types");
            }
            // add new mixin
            set.add(mixinName);
            // try to build new effective node type (will throw in case of conflicts)
            ntReg.getEffectiveNodeType((QName[]) set.toArray(new QName[set.size()]));
        } catch (NodeTypeConflictException ntce) {
            throw new ConstraintViolationException(ntce.getMessage());
        }

        // do the actual modifications implied by the new mixin;
        // try to revert the changes in case an excpetion occurs
        try {
            // modify the state of this node
            NodeState thisState = (NodeState) getOrCreateTransientItemState();
            // add mixin name
            Set mixins = new HashSet(thisState.getMixinTypeNames());
            mixins.add(mixinName);
            thisState.setMixinTypeNames(mixins);

            // set jcr:mixinTypes property
            setMixinTypesProperty(mixins);

            // add 'auto-create' properties defined in mixin type
            PropertyDefinition[] pda = mixin.getAutoCreatedPropertyDefinitions();
            for (int i = 0; i < pda.length; i++) {
                PropertyDefinitionImpl pd = (PropertyDefinitionImpl) pda[i];
                // make sure that the property is not already defined by primary type
                // or existing mixin's
                NodeTypeImpl declaringNT = (NodeTypeImpl) pd.getDeclaringNodeType();
                if (!entExisting.includesNodeType(declaringNT.getQName())) {
                    createChildProperty(pd.getQName(), pd.getRequiredType(), pd);
                }
            }

            // recursively add 'auto-create' child nodes defined in mixin type
            NodeDefinition[] nda = mixin.getAutoCreatedNodeDefinitions();
            for (int i = 0; i < nda.length; i++) {
                NodeDefinitionImpl nd = (NodeDefinitionImpl) nda[i];
                // make sure that the child node is not already defined by primary type
                // or existing mixin's
                NodeTypeImpl declaringNT = (NodeTypeImpl) nd.getDeclaringNodeType();
                if (!entExisting.includesNodeType(declaringNT.getQName())) {
                    createChildNode(nd.getQName(), nd, (NodeTypeImpl) nd.getDefaultPrimaryType(), null);
                }
            }
        } catch (RepositoryException re) {
            // try to undo the modifications by removing the mixin
            try {
                removeMixin(mixinName);
            } catch (RepositoryException re1) {
                // silently ignore & fall through
            }
            throw re;
        }
    }

    /**
     * Same as {@link Node#removeMixin(String)}, but takes a <code>QName</code>
     * instad of a <code>String</code>.
     *
     * @see Node#removeMixin(String)
     */
    public void removeMixin(QName mixinName)
            throws NoSuchNodeTypeException, VersionException,
            ConstraintViolationException, LockException, RepositoryException {
        // check state of this instance
        sanityCheck();

        // make sure this node is checked-out
        if (!internalIsCheckedOut()) {
            String msg = safeGetJCRPath()
                    + ": cannot remove a mixin node type from a checked-in node";
            log.debug(msg);
            throw new VersionException(msg);
        }

        // check protected flag
        if (definition.isProtected()) {
            String msg = safeGetJCRPath()
                    + ": cannot remove a mixin node type from a protected node";
            log.debug(msg);
            throw new ConstraintViolationException(msg);
        }

        // check lock status
        checkLock();

        // check if mixin is assigned
        if (!((NodeState) state).getMixinTypeNames().contains(mixinName)) {
            throw new NoSuchNodeTypeException();
        }

        NodeTypeManagerImpl ntMgr = session.getNodeTypeManager();
        NodeTypeRegistry ntReg = ntMgr.getNodeTypeRegistry();

        // build effective node type of remaining mixin's & primary type
        Set remainingMixins = new HashSet(((NodeState) state).getMixinTypeNames());
        // remove name of target mixin
        remainingMixins.remove(mixinName);
        EffectiveNodeType entRemaining;
        try {
            // remaining mixin's
            HashSet set = new HashSet(remainingMixins);
            // primary type
            set.add(nodeType.getQName());
            // build effective node type representing primary type including remaining mixin's
            entRemaining = ntReg.getEffectiveNodeType((QName[]) set.toArray(new QName[set.size()]));
        } catch (NodeTypeConflictException ntce) {
            throw new ConstraintViolationException(ntce.getMessage());
        }

        /**
         * mix:referenceable needs special handling because it has
         * special semantics:
         * it can only be removed if there no more references to this node
         */
        NodeTypeImpl mixin = ntMgr.getNodeType(mixinName);
        if ((MIX_REFERENCEABLE.equals(mixinName)
                || mixin.isDerivedFrom(MIX_REFERENCEABLE))
                && !entRemaining.includesNodeType(MIX_REFERENCEABLE)) {
            // removing this mixin would effectively remove mix:referenceable:
            // make sure no references exist
            PropertyIterator iter = getReferences();
            if (iter.hasNext()) {
                throw new ConstraintViolationException(
                        mixinName + " can not be removed: the node is being referenced through at least one property of type REFERENCE");
            }
        }

        // modify the state of this node
        NodeState thisState = (NodeState) getOrCreateTransientItemState();
        thisState.setMixinTypeNames(remainingMixins);

        // set jcr:mixinTypes property
        setMixinTypesProperty(remainingMixins);

        // shortcut
        if (mixin.getChildNodeDefinitions().length == 0
                && mixin.getPropertyDefinitions().length == 0) {
            // the node type has neither property nor child node definitions,
            // i.e. we're done
            return;
        }

        // walk through properties and child nodes and remove those that have been
        // defined by the specified mixin type

        // use temp array to avoid ConcurrentModificationException
        ArrayList tmp = new ArrayList(thisState.getPropertyEntries());
        Iterator iter = tmp.iterator();
        while (iter.hasNext()) {
            NodeState.PropertyEntry entry = (NodeState.PropertyEntry) iter.next();
            PropertyImpl prop = (PropertyImpl) itemMgr.getItem(new PropertyId(thisState.getUUID(), entry.getName()));
            // check if property has been defined by mixin type (or one of its supertypes)
            NodeTypeImpl declaringNT = (NodeTypeImpl) prop.getDefinition().getDeclaringNodeType();
            if (!entRemaining.includesNodeType(declaringNT.getQName())) {
                // the remaining effective node type doesn't include the
                // node type that declared this property, it is thus safe
                // to remove it
                removeChildProperty(entry.getName());
            }
        }
        // use temp array to avoid ConcurrentModificationException
        tmp = new ArrayList(thisState.getChildNodeEntries());
        iter = tmp.iterator();
        while (iter.hasNext()) {
            NodeState.ChildNodeEntry entry = (NodeState.ChildNodeEntry) iter.next();
            NodeImpl node = (NodeImpl) itemMgr.getItem(new NodeId(entry.getUUID()));
            // check if node has been defined by mixin type (or one of its supertypes)
            NodeTypeImpl declaringNT = (NodeTypeImpl) node.getDefinition().getDeclaringNodeType();
            if (!entRemaining.includesNodeType(declaringNT.getQName())) {
                // the remaining effective node type doesn't include the
                // node type that declared this child node, it is thus safe
                // to remove it
                removeChildNode(entry.getName(), entry.getIndex());
            }
        }
    }

    /**
     * Same as {@link Node#isNodeType(String)}, but takes a <code>QName</code>
     * instad of a <code>String</code>.
     *
     * @param ntName name of node type
     * @return <code>true</code> if this node is of the specified node type;
     *         otherwise <code>false</code>
     */
    public boolean isNodeType(QName ntName) throws RepositoryException {
        // check state of this instance
        sanityCheck();

        if (ntName.equals(nodeType.getQName())) {
            return true;
        }

        if (nodeType.isDerivedFrom(ntName)) {
            return true;
        }

        // check mixin types
        Set mixinNames = ((NodeState) state).getMixinTypeNames();
        if (mixinNames.isEmpty()) {
            return false;
        }
        NodeTypeRegistry ntReg = session.getNodeTypeManager().getNodeTypeRegistry();
        try {
            EffectiveNodeType ent = ntReg.getEffectiveNodeType((QName[]) mixinNames.toArray(new QName[mixinNames.size()]));
            return ent.includesNodeType(ntName);
        } catch (NodeTypeConflictException ntce) {
            String msg = "internal error: invalid mixin node type(s)";
            log.debug(msg);
            throw new RepositoryException(msg, ntce);
        }
    }

    /**
     * Returns the (internal) uuid of this node.
     *
     * @return the uuid of this node
     */
    public String internalGetUUID() {
        return ((NodeState) state).getUUID();
    }

    /**
     * Checks various pre-conditions that are common to all
     * <code>setProperty()</code> methods. The checks performed are:
     * <ul>
     * <li>this node must be checked-out</li>
     * <li>this node must not be locked by somebody else</li>
     * </ul>
     * Note that certain checks are performed by the respective
     * <code>Property.setValue()</code> methods. 
     *
     * @throws VersionException if this node is not checked-out
     * @throws LockException if this node is locked by somebody else
     * @throws RepositoryException if another error occurs
     *
     * @see javax.jcr.Node#setProperty
     */
    protected void checkSetProperty()
            throws VersionException, LockException, RepositoryException {
        // make sure this node is checked-out
        if (!internalIsCheckedOut()) {
            String msg = safeGetJCRPath()
                    + ": cannot set property of a checked-in node";
            log.debug(msg);
            throw new VersionException(msg);
        }

        // check lock status
        checkLock();
    }

    /**
     * Sets the internal value of a property without checking any constraints.
     * <p/>
     * Note that no type conversion is being performed, i.e. it's the caller's
     * responsibility to make sure that the type of the given value is compatible
     * with the specified property's definition.
     * <p/>
     * <b>Important:</b> This method is public in order to make it accessible
     * from internal code located in subpackages, i.e. it should never be called
     * from an application directly.
     *
     * @param name
     * @param value
     * @return
     * @throws ValueFormatException
     * @throws RepositoryException
     */
    public Property internalSetProperty(QName name, InternalValue value)
            throws ValueFormatException, RepositoryException {
        int type;
        if (value == null) {
            type = PropertyType.UNDEFINED;
        } else {
            type = value.getType();
        }

        BitSet status = new BitSet();
        PropertyImpl prop = getOrCreateProperty(name, type, false, status);
        try {
            if (value == null) {
                prop.internalSetValue(null, type);
            } else {
                prop.internalSetValue(new InternalValue[]{value}, type);
            }
        } catch (RepositoryException re) {
            if (status.get(CREATED)) {
                // setting value failed, get rid of newly created property
                removeChildProperty(name);
            }
            // rethrow
            throw re;
        }
        return prop;
    }

    /**
     * Sets the internal value of a property without checking any constraints.
     * <p/>
     * Note that no type conversion is being performed, i.e. it's the caller's
     * responsibility to make sure that the type of the given values is compatible
     * with the specified property's definition.
     * <p/>
     * <b>Important:</b> This method is for internal use only and should never
     * be called from an application directly.
     *
     * @param name
     * @param values
     * @return
     * @throws ValueFormatException
     * @throws RepositoryException
     */
    protected Property internalSetProperty(QName name, InternalValue[] values)
            throws ValueFormatException, RepositoryException {
        int type;
        if (values == null || values.length == 0
                || values[0] == null) {
            type = PropertyType.UNDEFINED;
        } else {
            type = values[0].getType();
        }
        return internalSetProperty(name, values, type);
    }

    /**
     * Sets the internal value of a property without checking any constraints.
     * <p/>
     * Note that no type conversion is being performed, i.e. it's the caller's
     * responsibility to make sure that the type of the given values is compatible
     * with the specified property's definition.
     *
     * @param name
     * @param values
     * @param type
     * @return
     * @throws ValueFormatException
     * @throws RepositoryException
     */
    protected Property internalSetProperty(QName name, InternalValue[] values,
                                           int type)
            throws ValueFormatException, RepositoryException {
        BitSet status = new BitSet();
        PropertyImpl prop = getOrCreateProperty(name, type, true, status);
        try {
            prop.internalSetValue(values, type);
        } catch (RepositoryException re) {
            if (status.get(CREATED)) {
                // setting value failed, get rid of newly created property
                removeChildProperty(name);
            }
            // rethrow
            throw re;
        }
        return prop;
    }

    /**
     * Returns the child node of <code>this</code> node with the specified
     * <code>name</code>.
     *
     * @param name The qualified name of the child node to retrieve.
     * @return The child node with the specified <code>name</code>.
     * @throws ItemNotFoundException If no child node exists with the
     *                               specified name.
     * @throws RepositoryException   If another error occurs.
     */
    public NodeImpl getNode(QName name) throws ItemNotFoundException, RepositoryException {
        return getNode(name, 1);
    }

    /**
     * Returns the child node of <code>this</code> node with the specified
     * <code>name</code>.
     *
     * @param name  The qualified name of the child node to retrieve.
     * @param index The index of the child node to retrieve (in the case of same-name siblings).
     * @return The child node with the specified <code>name</code>.
     * @throws ItemNotFoundException If no child node exists with the
     *                               specified name.
     * @throws RepositoryException   If another error occurs.
     */
    public NodeImpl getNode(QName name, int index)
            throws ItemNotFoundException, RepositoryException {
        // check state of this instance
        sanityCheck();

        NodeState thisState = (NodeState) state;
        NodeState.ChildNodeEntry cne = thisState.getChildNodeEntry(name, index);
        if (cne == null) {
            throw new ItemNotFoundException();
        }
        NodeId nodeId = new NodeId(cne.getUUID());
        try {
            return (NodeImpl) itemMgr.getItem(nodeId);
        } catch (AccessDeniedException ade) {
            throw new ItemNotFoundException();
        }
    }

    /**
     * Indicates whether a child node with the specified <code>name</code> exists.
     * Returns <code>true</code> if the child node exists and <code>false</code>
     * otherwise.
     *
     * @param name The qualified name of the child node.
     * @return <code>true</code> if the child node exists; <code>false</code> otherwise.
     * @throws RepositoryException If an unspecified error occurs.
     */
    public boolean hasNode(QName name) throws RepositoryException {
        return hasNode(name, 1);
    }

    /**
     * Indicates whether a child node with the specified <code>name</code> exists.
     * Returns <code>true</code> if the child node exists and <code>false</code>
     * otherwise.
     *
     * @param name  The qualified name of the child node.
     * @param index The index of the child node (in the case of same-name siblings).
     * @return <code>true</code> if the child node exists; <code>false</code> otherwise.
     * @throws RepositoryException If an unspecified error occurs.
     */
    public boolean hasNode(QName name, int index) throws RepositoryException {
        // check state of this instance
        sanityCheck();

        NodeState thisState = (NodeState) state;
        NodeState.ChildNodeEntry cne = thisState.getChildNodeEntry(name, index);
        if (cne == null) {
            return false;
        }
        NodeId nodeId = new NodeId(cne.getUUID());

        return itemMgr.itemExists(nodeId);
    }

    /**
     * Returns the property of <code>this</code> node with the specified
     * <code>name</code>.
     *
     * @param name The qualified name of the property to retrieve.
     * @return The property with the specified <code>name</code>.
     * @throws ItemNotFoundException If no property exists with the
     *                               specified name.
     * @throws RepositoryException   If another error occurs.
     */
    public PropertyImpl getProperty(QName name)
            throws ItemNotFoundException, RepositoryException {
        // check state of this instance
        sanityCheck();

        NodeState thisState = (NodeState) state;
        PropertyId propId = new PropertyId(thisState.getUUID(), name);
        try {
            return (PropertyImpl) itemMgr.getItem(propId);
        } catch (AccessDeniedException ade) {
            throw new ItemNotFoundException(name.toString());
        }
    }

    /**
     * Indicates whether a property with the specified <code>name</code> exists.
     * Returns <code>true</code> if the property exists and <code>false</code>
     * otherwise.
     *
     * @param name The qualified name of the property.
     * @return <code>true</code> if the property exists; <code>false</code> otherwise.
     * @throws RepositoryException If an unspecified error occurs.
     */
    public boolean hasProperty(QName name) throws RepositoryException {
        // check state of this instance
        sanityCheck();

        NodeState thisState = (NodeState) state;
        if (!thisState.hasPropertyEntry(name)) {
            return false;
        }
        PropertyId propId = new PropertyId(thisState.getUUID(), name);

        return itemMgr.itemExists(propId);
    }

    /**
     * Same as <code>{@link Node#addNode(String, String)}</code> except that
     * this method takes <code>QName</code> arguments instead of
     * <code>String</code>s and has an additional <code>uuid</code> argument.
     * <p/>
     * <b>Important Notice:</b> This method is for internal use only! Passing
     * already assigned uuid's might lead to unexpected results and
     * data corruption in the worst case.
     *
     * @param nodeName     name of the new node
     * @param nodeTypeName name of the new node's node type or <code>null</code>
     *                     if it should be determined automatically
     * @param uuid         uuid of the new node or <code>null</code> if a new
     *                     uuid should be assigned
     * @return the newly added node
     * @throws ItemExistsException
     * @throws NoSuchNodeTypeException
     * @throws VersionException
     * @throws ConstraintViolationException
     * @throws LockException
     * @throws RepositoryException
     */
    public synchronized NodeImpl addNode(QName nodeName, QName nodeTypeName,
                                         String uuid)
            throws ItemExistsException, NoSuchNodeTypeException, VersionException,
            ConstraintViolationException, LockException, RepositoryException {
        // check state of this instance
        sanityCheck();

        // make sure this node is checked-out
        if (!internalIsCheckedOut()) {
            String msg = safeGetJCRPath() + ": cannot add node to a checked-in node";
            log.debug(msg);
            throw new VersionException(msg);
        }

        // check lock status
        checkLock();

        NodeTypeImpl nt = null;
        if (nodeTypeName != null) {
            nt = session.getNodeTypeManager().getNodeType(nodeTypeName);
        }
        return internalAddChildNode(nodeName, nt, uuid);
    }

    /**
     * Same as <code>{@link Node#setProperty(String, Value[])}</code> except that
     * this method takes a <code>QName</code> name argument instead of a
     * <code>String</code>.
     *
     * @param name
     * @param values
     * @return
     * @throws ValueFormatException
     * @throws VersionException
     * @throws LockException
     * @throws ConstraintViolationException
     * @throws RepositoryException
     */
    public PropertyImpl setProperty(QName name, Value[] values)
            throws ValueFormatException, VersionException, LockException,
            ConstraintViolationException, RepositoryException {

        int type;
        if (values == null || values.length == 0
                || values[0] == null) {
            type = PropertyType.UNDEFINED;
        } else {
            type = values[0].getType();
        }
        return setProperty(name, values, type);
    }

    /**
     * Same as <code>{@link Node#setProperty(String, Value[], int)}</code> except
     * that this method takes a <code>QName</code> name argument instead of a
     * <code>String</code>.
     *
     * @param name
     * @param values
     * @param type
     * @return
     * @throws ValueFormatException
     * @throws VersionException
     * @throws LockException
     * @throws ConstraintViolationException
     * @throws RepositoryException
     */
    public PropertyImpl setProperty(QName name, Value[] values, int type)
            throws ValueFormatException, VersionException, LockException,
            ConstraintViolationException, RepositoryException {
        // check state of this instance
        sanityCheck();

        // check pre-conditions for setting property
        checkSetProperty();

        BitSet status = new BitSet();
        PropertyImpl prop = getOrCreateProperty(name, type, true, status);
        try {
            prop.setValue(values);
        } catch (RepositoryException re) {
            if (status.get(CREATED)) {
                // setting value failed, get rid of newly created property
                removeChildProperty(name);
            }
            // rethrow
            throw re;
        }
        return prop;
    }

    /**
     * Same as <code>{@link Node#setProperty(String, Value)}</code> except that
     * this method takes a <code>QName</code> name argument instead of a
     * <code>String</code>.
     *
     * @param name
     * @param value
     * @return
     * @throws ValueFormatException
     * @throws VersionException
     * @throws LockException
     * @throws ConstraintViolationException
     * @throws RepositoryException
     */
    public PropertyImpl setProperty(QName name, Value value)
            throws ValueFormatException, VersionException, LockException,
            ConstraintViolationException, RepositoryException {
        // check state of this instance
        sanityCheck();

        // check pre-conditions for setting property
        checkSetProperty();

        int type = PropertyType.UNDEFINED;
        if (value != null) {
            type = value.getType();
        }

        BitSet status = new BitSet();
        PropertyImpl prop = getOrCreateProperty(name, type, false, status);
        try {
            prop.setValue(value);
        } catch (RepositoryException re) {
            if (status.get(CREATED)) {
                // setting value failed, get rid of newly created property
                removeChildProperty(name);
            }
            // rethrow
            throw re;
        }
        return prop;
    }

    /**
     * @see ItemImpl#getQName()
     */
    public QName getQName() throws RepositoryException {
        return session.getHierarchyManager().getName(id);
    }

    //-----------------------------------------------------------------< Item >
    /**
     * {@inheritDoc}
     */
    public boolean isNode() {
        return true;
    }

    /**
     * {@inheritDoc}
     */
    public String getName() throws RepositoryException {
        // check state of this instance
        sanityCheck();

        if (state.getParentUUID() == null) {
            // this is the root node
            return "";
        }

        QName name = session.getHierarchyManager().getName(id);
        try {
            return name.toJCRName(session.getNamespaceResolver());
        } catch (NoPrefixDeclaredException npde) {
            // should never get here...
            String msg = "internal error: encountered unregistered namespace "
                    + name.getNamespaceURI();
            log.debug(msg);
            throw new RepositoryException(msg, npde);
        }
    }

    /**
     * {@inheritDoc}
     */
    public void accept(ItemVisitor visitor) throws RepositoryException {
        // check state of this instance
        sanityCheck();

        visitor.visit(this);
    }

    /**
     * {@inheritDoc}
     */
    public Node getParent()
            throws ItemNotFoundException, AccessDeniedException, RepositoryException {
        // check state of this instance
        sanityCheck();

        // check if root node
        NodeState thisState = (NodeState) state;
        if (thisState.getParentUUID() == null) {
            String msg = "root node doesn't have a parent";
            log.debug(msg);
            throw new ItemNotFoundException(msg);
        }

        Path path = getPrimaryPath();
        return (Node) getAncestor(path.getAncestorCount() - 1);
    }

    //-----------------------------------------------------------------< Node >
    /**
     * {@inheritDoc}
     */
    public synchronized Node addNode(String relPath)
            throws ItemExistsException, PathNotFoundException, VersionException,
            ConstraintViolationException, LockException, RepositoryException {
        // check state of this instance
        sanityCheck();

        return internalAddNode(relPath, null);
    }

    /**
     * {@inheritDoc}
     */
    public synchronized Node addNode(String relPath, String nodeTypeName)
            throws ItemExistsException, PathNotFoundException,
            NoSuchNodeTypeException, VersionException,
            ConstraintViolationException, LockException, RepositoryException {
        // check state of this instance
        sanityCheck();

        NodeTypeImpl nt = (NodeTypeImpl) session.getNodeTypeManager().getNodeType(nodeTypeName);
        return internalAddNode(relPath, nt);
    }

    /**
     * {@inheritDoc}
     */
    public synchronized void orderBefore(String srcName, String destName)
            throws UnsupportedRepositoryOperationException, VersionException,
            ConstraintViolationException, ItemNotFoundException, LockException,
            RepositoryException {

        if (!nodeType.hasOrderableChildNodes()) {
            throw new UnsupportedRepositoryOperationException("child node ordering not supported on node " + safeGetJCRPath());
        }

        // check arguments
        if (srcName.equals(destName)) {
            throw new ConstraintViolationException("source and destination have to be different");
        }

        Path.PathElement insertName;
        try {
            Path p = Path.create(srcName, session.getNamespaceResolver(), false);
            // p must be a relative path of length==depth==1 (to eliminate e.g. "..")
            if (p.isAbsolute() || p.getLength() != 1 || p.getDepth() != 1) {
                throw new RepositoryException("invalid name: " + srcName);
            }
            insertName = p.getNameElement();
        } catch (MalformedPathException e) {
            String msg = "invalid name: " + srcName;
            log.debug(msg);
            throw new RepositoryException(msg, e);
        }

        Path.PathElement beforeName;
        if (destName != null) {
            try {
                Path p = Path.create(destName, session.getNamespaceResolver(), false);
                // p must be a relative path of length==depth==1 (to eliminate e.g. "..")
                if (p.isAbsolute() || p.getLength() != 1 || p.getDepth() != 1) {
                    throw new RepositoryException("invalid name: " + destName);
                }
                beforeName = p.getNameElement();
            } catch (MalformedPathException e) {
                String msg = "invalid name: " + destName;
                log.debug(msg);
                throw new RepositoryException(msg, e);
            }
        } else {
            beforeName = null;
        }

        // check existence
        if (!hasNode(srcName)) {
            throw new ItemNotFoundException(safeGetJCRPath() + " has no child node with name " + srcName);
        }
        if (destName != null && !hasNode(destName)) {
            throw new ItemNotFoundException(safeGetJCRPath() + " has no child node with name " + destName);
        }

        // make sure this node is checked-out
        if (!internalIsCheckedOut()) {
            String msg = safeGetJCRPath() + ": cannot change child node ordering of a checked-in node";
            log.debug(msg);
            throw new VersionException(msg);
        }

        // check protected flag
        if (definition.isProtected()) {
            String msg = safeGetJCRPath() + ": cannot change child node ordering of a protected node";
            log.debug(msg);
            throw new ConstraintViolationException(msg);
        }

        // check lock status
        checkLock();

        ArrayList list = new ArrayList(((NodeState) state).getChildNodeEntries());
        int srcInd = -1, destInd = -1;
        for (int i = 0; i < list.size(); i++) {
            NodeState.ChildNodeEntry entry = (NodeState.ChildNodeEntry) list.get(i);
            if (srcInd == -1) {
                if (entry.getName().equals(insertName.getName())
                        && (entry.getIndex() == insertName.getIndex()
                        || insertName.getIndex() == 0 && entry.getIndex() == 1)) {
                    srcInd = i;
                }
            }
            if (destInd == -1 && beforeName != null) {
                if (entry.getName().equals(beforeName.getName())
                        && (entry.getIndex() == beforeName.getIndex()
                        || beforeName.getIndex() == 0 && entry.getIndex() == 1)) {
                    destInd = i;
                    if (srcInd != -1) {
                        break;
                    }
                }
            } else {
                if (srcInd != -1) {
                    break;
                }
            }
        }

        // check if resulting order would be different to current order
        if (destInd == -1) {
            if (srcInd == list.size() - 1) {
                // no change, we're done
                return;
            }
        } else {
            if ((destInd - srcInd) == 1) {
                // no change, we're done
                return;
            }
        }

        // reorder list
        if (destInd == -1) {
            list.add(list.remove(srcInd));
        } else {
            if (srcInd < destInd) {
                list.add(destInd, list.get(srcInd));
                list.remove(srcInd);
            } else {
                list.add(destInd, list.remove(srcInd));
            }
        }

        // modify the state of 'this', i.e. the parent node
        NodeState thisState = (NodeState) getOrCreateTransientItemState();
        thisState.setChildNodeEntries(list);
    }

    /**
     * {@inheritDoc}
     */
    public Property setProperty(String name, Value[] values)
            throws ValueFormatException, VersionException, LockException,
            ConstraintViolationException, RepositoryException {
        int type;
        if (values == null || values.length == 0
                || values[0] == null) {
            type = PropertyType.UNDEFINED;
        } else {
            type = values[0].getType();
        }
        return setProperty(name, values, type);
    }

    /**
     * {@inheritDoc}
     */
    public Property setProperty(String name, Value[] values, int type)
            throws ValueFormatException, VersionException, LockException,
            ConstraintViolationException, RepositoryException {
        // check state of this instance
        sanityCheck();

        // check pre-conditions for setting property
        checkSetProperty();

        BitSet status = new BitSet();
        PropertyImpl prop = getOrCreateProperty(name, type, true, status);
        try {
            if (type == PropertyType.UNDEFINED) {
                prop.setValue(values);
            } else {
                prop.setValue(ValueHelper.convert(values, type));
            }
        } catch (RepositoryException re) {
            if (status.get(CREATED)) {
                // setting value failed, get rid of newly created property
                removeChildProperty(name);
            }
            // rethrow
            throw re;
        }
        return prop;
    }

    /**
     * {@inheritDoc}
     */
    public Property setProperty(String name, String[] values)
            throws ValueFormatException, VersionException, LockException,
            ConstraintViolationException, RepositoryException {
        /**
         * if the target property is not of type STRING then a
         * best-effort conversion is tried
         */
        return setProperty(name, values, PropertyType.UNDEFINED);
    }

    /**
     * {@inheritDoc}
     */
    public Property setProperty(String name, String[] values, int type)
            throws ValueFormatException, VersionException, LockException,
            ConstraintViolationException, RepositoryException {
        // check state of this instance
        sanityCheck();

        // check pre-conditions for setting property
        checkSetProperty();

        BitSet status = new BitSet();
        PropertyImpl prop = getOrCreateProperty(name, type, true, status);
        try {
            if (type == PropertyType.UNDEFINED) {
                prop.setValue(values);
            } else {
                prop.setValue(ValueHelper.convert(values, type));
            }
        } catch (RepositoryException re) {
            if (status.get(CREATED)) {
                // setting value failed, get rid of newly created property
                removeChildProperty(name);
            }
            // rethrow
            throw re;
        }
        return prop;
    }

    /**
     * {@inheritDoc}
     */
    public Property setProperty(String name, String value)
            throws ValueFormatException, VersionException, LockException,
            ConstraintViolationException, RepositoryException {
        /**
         * if the target property is not of type STRING then a
         * best-effort conversion is tried
         */
        return setProperty(name, value, PropertyType.UNDEFINED);
    }

    /**
     * {@inheritDoc}
     */
    public Property setProperty(String name, String value, int type)
            throws ValueFormatException, VersionException, LockException,
            ConstraintViolationException, RepositoryException {
        // check state of this instance
        sanityCheck();

        // check pre-conditions for setting property
        checkSetProperty();

        BitSet status = new BitSet();
        PropertyImpl prop = getOrCreateProperty(name, type, false, status);
        try {
            if (type == PropertyType.UNDEFINED) {
                prop.setValue(value);
            } else {
                prop.setValue(ValueHelper.convert(value, type));
            }
        } catch (RepositoryException re) {
            if (status.get(CREATED)) {
                // setting value failed, get rid of newly created property
                removeChildProperty(name);
            }
            // rethrow
            throw re;
        }
        return prop;
    }

    /**
     * {@inheritDoc}
     */
    public Property setProperty(String name, Value value, int type)
            throws ValueFormatException, VersionException, LockException,
            ConstraintViolationException, RepositoryException {
        // check state of this instance
        sanityCheck();

        // check pre-conditions for setting property
        checkSetProperty();

        BitSet status = new BitSet();
        PropertyImpl prop = getOrCreateProperty(name, type, false, status);
        try {
            if (type == PropertyType.UNDEFINED) {
                prop.setValue(value);
            } else {
                prop.setValue(ValueHelper.convert(value, type));
            }
        } catch (RepositoryException re) {
            if (status.get(CREATED)) {
                // setting value failed, get rid of newly created property
                removeChildProperty(name);
            }
            // rethrow
            throw re;
        }
        return prop;
    }

    /**
     * {@inheritDoc}
     */
    public Property setProperty(String name, Value value)
            throws ValueFormatException, VersionException, LockException,
            ConstraintViolationException, RepositoryException {
        int type = PropertyType.UNDEFINED;
        if (value != null) {
            type = value.getType();
        }
        return setProperty(name, value, type);
    }

    /**
     * {@inheritDoc}
     */
    public Property setProperty(String name, InputStream value)
            throws ValueFormatException, VersionException, LockException,
            ConstraintViolationException, RepositoryException {
        // check state of this instance
        sanityCheck();

        // check pre-conditions for setting property
        checkSetProperty();

        BitSet status = new BitSet();
        PropertyImpl prop = getOrCreateProperty(name, PropertyType.BINARY, false, status);
        try {
            prop.setValue(value);
        } catch (RepositoryException re) {
            if (status.get(CREATED)) {
                // setting value failed, get rid of newly created property
                removeChildProperty(name);
            }
            // rethrow
            throw re;
        }
        return prop;
    }

    /**
     * {@inheritDoc}
     */
    public Property setProperty(String name, boolean value)
            throws ValueFormatException, VersionException, LockException,
            ConstraintViolationException, RepositoryException {
        // check state of this instance
        sanityCheck();

        // check pre-conditions for setting property
        checkSetProperty();

        BitSet status = new BitSet();
        PropertyImpl prop = getOrCreateProperty(name, PropertyType.BOOLEAN, false, status);
        try {
            prop.setValue(value);
        } catch (RepositoryException re) {
            if (status.get(CREATED)) {
                // setting value failed, get rid of newly created property
                removeChildProperty(name);
            }
            // rethrow
            throw re;
        }
        return prop;
    }

    /**
     * {@inheritDoc}
     */
    public Property setProperty(String name, double value)
            throws ValueFormatException, VersionException, LockException,
            ConstraintViolationException, RepositoryException {
        // check state of this instance
        sanityCheck();

        // check pre-conditions for setting property
        checkSetProperty();

        BitSet status = new BitSet();
        PropertyImpl prop = getOrCreateProperty(name, PropertyType.DOUBLE, false, status);
        try {
            prop.setValue(value);
        } catch (RepositoryException re) {
            if (status.get(CREATED)) {
                // setting value failed, get rid of newly created property
                removeChildProperty(name);
            }
            // rethrow
            throw re;
        }
        return prop;
    }

    /**
     * {@inheritDoc}
     */
    public Property setProperty(String name, long value)
            throws ValueFormatException, VersionException, LockException,
            ConstraintViolationException, RepositoryException {
        // check state of this instance
        sanityCheck();

        // check pre-conditions for setting property
        checkSetProperty();

        BitSet status = new BitSet();
        PropertyImpl prop = getOrCreateProperty(name, PropertyType.LONG, false, status);
        try {
            prop.setValue(value);
        } catch (RepositoryException re) {
            if (status.get(CREATED)) {
                // setting value failed, get rid of newly created property
                removeChildProperty(name);
            }
            // rethrow
            throw re;
        }
        return prop;
    }

    /**
     * {@inheritDoc}
     */
    public Property setProperty(String name, Calendar value)
            throws ValueFormatException, VersionException, LockException,
            ConstraintViolationException, RepositoryException {
        // check state of this instance
        sanityCheck();

        // check pre-conditions for setting property
        checkSetProperty();

        BitSet status = new BitSet();
        PropertyImpl prop = getOrCreateProperty(name, PropertyType.DATE, false, status);
        try {
            prop.setValue(value);
        } catch (RepositoryException re) {
            if (status.get(CREATED)) {
                // setting value failed, get rid of newly created property
                removeChildProperty(name);
            }
            // rethrow
            throw re;
        }
        return prop;
    }

    /**
     * {@inheritDoc}
     */
    public Property setProperty(String name, Node value)
            throws ValueFormatException, VersionException, LockException,
            ConstraintViolationException, RepositoryException {
        // check state of this instance
        sanityCheck();

        // check pre-conditions for setting property
        checkSetProperty();

        BitSet status = new BitSet();
        PropertyImpl prop = getOrCreateProperty(name, PropertyType.REFERENCE, false, status);
        try {
            prop.setValue(value);
        } catch (RepositoryException re) {
            if (status.get(CREATED)) {
                // setting value failed, get rid of newly created property
                removeChildProperty(name);
            }
            // rethrow
            throw re;
        }
        return prop;
    }

    /**
     * {@inheritDoc}
     */
    public Node getNode(String relPath)
            throws PathNotFoundException, RepositoryException {
        // check state of this instance
        sanityCheck();
        NodeId id = resolveRelativeNodePath(relPath);
        if (id == null) {
            throw new PathNotFoundException(relPath);
        }
        try {
            return (Node) itemMgr.getItem(id);
        } catch (AccessDeniedException ade) {
            throw new PathNotFoundException(relPath);
        }
    }

    /**
     * {@inheritDoc}
     */
    public NodeIterator getNodes() throws RepositoryException {
        // check state of this instance
        sanityCheck();

        /**
         * IMPORTANT:
         * an implementation of Node.getNodes()
         * must not use a class derived from TraversingElementVisitor
         * to traverse the hierarchy because this would lead to an infinite
         * recursion!
         */
        try {
            return itemMgr.getChildNodes((NodeId) id);
        } catch (ItemNotFoundException infe) {
            String msg = "failed to list the child nodes of " + safeGetJCRPath();
            log.debug(msg);
            throw new RepositoryException(msg, infe);
        } catch (AccessDeniedException ade) {
            String msg = "failed to list the child nodes of " + safeGetJCRPath();
            log.debug(msg);
            throw new RepositoryException(msg, ade);
        }
    }

    /**
     * {@inheritDoc}
     */
    public PropertyIterator getProperties() throws RepositoryException {
        // check state of this instance
        sanityCheck();

        /**
         * IMPORTANT:
         * an implementation of Node.getProperties()
         * must not use a class derived from TraversingElementVisitor
         * to traverse the hierarchy because this would lead to an infinite
         * recursion!
         */
        try {
            return itemMgr.getChildProperties((NodeId) id);
        } catch (ItemNotFoundException infe) {
            String msg = "failed to list the child properties of " + safeGetJCRPath();
            log.debug(msg);
            throw new RepositoryException(msg, infe);
        } catch (AccessDeniedException ade) {
            String msg = "failed to list the child properties of " + safeGetJCRPath();
            log.debug(msg);
            throw new RepositoryException(msg, ade);
        }
    }

    /**
     * {@inheritDoc}
     */
    public Property getProperty(String relPath)
            throws PathNotFoundException, RepositoryException {
        // check state of this instance
        sanityCheck();

        PropertyId id = resolveRelativePropertyPath(relPath);
        if (id == null) {
            throw new PathNotFoundException(relPath);
        }
        try {
            return (Property) itemMgr.getItem(id);
        } catch (AccessDeniedException ade) {
            throw new PathNotFoundException(relPath);
        }
    }

    /**
     * {@inheritDoc}
     */
    public boolean hasNode(String relPath) throws RepositoryException {
        // check state of this instance
        sanityCheck();

        NodeId id = resolveRelativeNodePath(relPath);
        if (id != null) {
            return itemMgr.itemExists(id);
        } else {
            return false;
        }
    }

    /**
     * {@inheritDoc}
     */
    public boolean hasNodes() throws RepositoryException {
        // check state of this instance
        sanityCheck();

        /**
         * hasNodes respects the access rights
         * of this node's session, i.e. it will
         * return false if child nodes exist
         * but the session is not granted read-access
         */
        return itemMgr.hasChildNodes((NodeId) id);
    }

    /**
     * {@inheritDoc}
     */
    public boolean hasProperties() throws RepositoryException {
        // check state of this instance
        sanityCheck();

        /**
         * hasProperties respects the access rights
         * of this node's session, i.e. it will
         * return false if properties exist
         * but the session is not granted read-access
         */
        return itemMgr.hasChildProperties((NodeId) id);
    }

    /**
     * {@inheritDoc}
     */
    public boolean isNodeType(String nodeTypeName) throws RepositoryException {
        QName ntName;
        try {
            ntName = QName.fromJCRName(nodeTypeName, session.getNamespaceResolver());
        } catch (IllegalNameException ine) {
            throw new RepositoryException("invalid node type name: " + nodeTypeName, ine);
        } catch (UnknownPrefixException upe) {
            throw new RepositoryException("invalid node type name: " + nodeTypeName, upe);
        }
        return isNodeType(ntName);
    }

    /**
     * {@inheritDoc}
     */
    public NodeType getPrimaryNodeType() throws RepositoryException {
        // check state of this instance
        sanityCheck();

        return nodeType;
    }

    /**
     * {@inheritDoc}
     */
    public NodeType[] getMixinNodeTypes() throws RepositoryException {
        // check state of this instance
        sanityCheck();

        Set mixinNames = ((NodeState) state).getMixinTypeNames();
        if (mixinNames.isEmpty()) {
            return new NodeType[0];
        }
        NodeType[] nta = new NodeType[mixinNames.size()];
        Iterator iter = mixinNames.iterator();
        int i = 0;
        while (iter.hasNext()) {
            nta[i++] = session.getNodeTypeManager().getNodeType((QName) iter.next());
        }
        return nta;
    }

    /**
     * {@inheritDoc}
     */
    public void addMixin(String mixinName)
            throws NoSuchNodeTypeException, VersionException,
            ConstraintViolationException, LockException, RepositoryException {
        QName ntName;
        try {
            ntName = QName.fromJCRName(mixinName, session.getNamespaceResolver());
        } catch (IllegalNameException ine) {
            throw new RepositoryException("invalid mixin type name: " + mixinName, ine);
        } catch (UnknownPrefixException upe) {
            throw new RepositoryException("invalid mixin type name: " + mixinName, upe);
        }

        addMixin(ntName);
    }

    /**
     * {@inheritDoc}
     */
    public void removeMixin(String mixinName)
            throws NoSuchNodeTypeException, VersionException,
            ConstraintViolationException, LockException, RepositoryException {
        QName ntName;
        try {
            ntName = QName.fromJCRName(mixinName, session.getNamespaceResolver());
        } catch (IllegalNameException ine) {
            throw new RepositoryException("invalid mixin type name: " + mixinName, ine);
        } catch (UnknownPrefixException upe) {
            throw new RepositoryException("invalid mixin type name: " + mixinName, upe);
        }

        removeMixin(ntName);
    }

    /**
     * {@inheritDoc}
     */
    public boolean canAddMixin(String mixinName)
            throws NoSuchNodeTypeException, RepositoryException {
        // check state of this instance
        sanityCheck();

        // check checked-out status
        if (!internalIsCheckedOut()) {
            return false;
        }

        // check protected flag
        if (definition.isProtected()) {
            return false;
        }

        // check lock status
        if (isLocked()) {
            return false;
        }

        QName ntName;
        try {
            ntName = QName.fromJCRName(mixinName, session.getNamespaceResolver());
        } catch (IllegalNameException ine) {
            throw new RepositoryException("invalid mixin type name: "
                    + mixinName, ine);
        } catch (UnknownPrefixException upe) {
            throw new RepositoryException("invalid mixin type name: "
                    + mixinName, upe);
        }

        NodeTypeManagerImpl ntMgr = session.getNodeTypeManager();
        NodeTypeImpl mixin = ntMgr.getNodeType(ntName);
        if (!mixin.isMixin()) {
            return false;
        }
        if (nodeType.isDerivedFrom(ntName)) {
            return false;
        }

        // build effective node type of mixins & primary type
        // in order to detect conflicts
        NodeTypeRegistry ntReg = ntMgr.getNodeTypeRegistry();
        EffectiveNodeType entExisting;
        try {
            // existing mixin's
            HashSet set = new HashSet(((NodeState) state).getMixinTypeNames());
            // primary type
            set.add(nodeType.getQName());
            // build effective node type representing primary type including existing mixin's
            entExisting = ntReg.getEffectiveNodeType((QName[]) set.toArray(new QName[set.size()]));
            if (entExisting.includesNodeType(ntName)) {
                return false;
            }
            // add new mixin
            set.add(ntName);
            // try to build new effective node type (will throw in case of conflicts)
            ntReg.getEffectiveNodeType((QName[]) set.toArray(new QName[set.size()]));
        } catch (NodeTypeConflictException ntce) {
            return false;
        }

        return true;
    }

    /**
     * {@inheritDoc}
     */
    public boolean hasProperty(String relPath) throws RepositoryException {
        // check state of this instance
        sanityCheck();

        PropertyId id = resolveRelativePropertyPath(relPath);
        if (id != null) {
            return itemMgr.itemExists(id);
        } else {
            return false;
        }
    }

    /**
     * {@inheritDoc}
     */
    public PropertyIterator getReferences() throws RepositoryException {
        // check state of this instance
        sanityCheck();

        return getReferences(false);
    }

    /**
     * {@inheritDoc}
     */
    public NodeDefinition getDefinition() throws RepositoryException {
        // check state of this instance
        sanityCheck();

        return definition;
    }

    /**
     * {@inheritDoc}
     */
    public NodeIterator getNodes(String namePattern) throws RepositoryException {
        // check state of this instance
        sanityCheck();

        ArrayList nodes = new ArrayList();
        // traverse children using a special filtering 'collector'
        accept(new ChildrenCollectorFilter(namePattern, nodes, true, false, 1));
        return new IteratorHelper(Collections.unmodifiableList(nodes));
    }

    /**
     * {@inheritDoc}
     */
    public PropertyIterator getProperties(String namePattern)
            throws RepositoryException {
        // check state of this instance
        sanityCheck();

        ArrayList properties = new ArrayList();
        // traverse children using a special filtering 'collector'
        accept(new ChildrenCollectorFilter(namePattern, properties, false, true, 1));
        return new IteratorHelper(Collections.unmodifiableList(properties));
    }

    /**
     * {@inheritDoc}
     */
    public Item getPrimaryItem()
            throws ItemNotFoundException, RepositoryException {
        // check state of this instance
        sanityCheck();

        String name = nodeType.getPrimaryItemName();
        if (name == null) {
            throw new ItemNotFoundException();
        }
        if (hasProperty(name)) {
            return getProperty(name);
        } else if (hasNode(name)) {
            return getNode(name);
        } else {
            throw new ItemNotFoundException();
        }
    }

    /**
     * {@inheritDoc}
     */
    public String getUUID()
            throws UnsupportedRepositoryOperationException, RepositoryException {
        // check state of this instance
        sanityCheck();

        if (!isNodeType(MIX_REFERENCEABLE)) {
            throw new UnsupportedRepositoryOperationException();
        }

        return ((NodeState) state).getUUID();
    }

    /**
     * {@inheritDoc}
     */
    public String getCorrespondingNodePath(String workspaceName)
            throws ItemNotFoundException, NoSuchWorkspaceException,
            AccessDeniedException, RepositoryException {
        // check state of this instance
        sanityCheck();

        SessionImpl srcSession = null;
        try {
            // create session on other workspace for current subject
            // (may throw NoSuchWorkspaceException and AccessDeniedException)
            srcSession = rep.createSession(session.getSubject(), workspaceName);

            // search nearest ancestor that is referenceable
            NodeImpl m1 = this;
            while (m1.getDepth() != 0 && !m1.isNodeType(MIX_REFERENCEABLE)) {
                m1 = (NodeImpl) m1.getParent();
            }

            // if root is common ancestor, corresponding path is same as ours
            if (m1.getDepth() == 0) {
                // check existence
                if (!srcSession.getItemManager().itemExists(getPrimaryPath())) {
                    throw new ItemNotFoundException(safeGetJCRPath());
                } else {
                    return getPath();
                }
            }

            // get corresponding ancestor
            Node m2 = srcSession.getNodeByUUID(m1.getUUID());

            // return path of m2, if m1 == n1
            if (m1 == this) {
                return m2.getPath();
            }

            String relPath;
            try {
                Path p = m1.getPrimaryPath().computeRelativePath(getPrimaryPath());
                // use prefix mappings of srcSession
                relPath = p.toJCRPath(srcSession.getNamespaceResolver());
            } catch (BaseException be) {
                // should never get here...
                String msg = "internal error: failed to determine relative path";
                log.error(msg, be);
                throw new RepositoryException(msg, be);
            }

            if (!m2.hasNode(relPath)) {
                throw new ItemNotFoundException();
            } else {
                return m2.getNode(relPath).getPath();
            }
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
    public int getIndex() throws RepositoryException {
        // check state of this instance
        sanityCheck();

        // @todo optimize, no need to build entire path just to find this node's index
        int index = getPrimaryPath().getNameElement().getIndex();
        if (index == 0) {
            return 1;
        } else {
            return index;
        }
    }

    //------------------------------< versioning support: public Node methods >
    /**
     * {@inheritDoc}
     */
    public Version checkin()
            throws VersionException, UnsupportedRepositoryOperationException,
            InvalidItemStateException, LockException, RepositoryException {
        // check state of this instance
        sanityCheck();

        // check if versionable
        checkVersionable();

        // check if checked out
        if (!internalIsCheckedOut()) {
            String msg = safeGetJCRPath() + ": Node is already checked-in. ignoring.";
            log.debug(msg);
            return getBaseVersion();
        }

        // check for pending changes
        if (hasPendingChanges()) {
            String msg = "Unable to checkin node. Node has pending changes: " + safeGetJCRPath();
            log.debug(msg);
            throw new InvalidItemStateException(msg);
        }

        // check lock status
        checkLock();

        Version v = session.getVersionManager().checkin(this);
        Property prop = internalSetProperty(JCR_ISCHECKEDOUT, InternalValue.create(false));
        prop.save();
        prop = internalSetProperty(JCR_BASEVERSION, InternalValue.create(new UUID(v.getUUID())));
        prop.save();
        prop = internalSetProperty(JCR_PREDECESSORS, InternalValue.EMPTY_ARRAY, PropertyType.REFERENCE);
        prop.save();
        return v;
    }

    /**
     * {@inheritDoc}
     */
    public void checkout()
            throws UnsupportedRepositoryOperationException, LockException,
            RepositoryException {
        // check state of this instance
        sanityCheck();

        // check if versionable
        checkVersionable();

        // check checked-out status
        if (internalIsCheckedOut()) {
            String msg = safeGetJCRPath() + ": Node is already checked-out. ignoring.";
            log.debug(msg);
            return;
        }

        // check lock status
        checkLock();

        Property prop = internalSetProperty(JCR_ISCHECKEDOUT, InternalValue.create(true));
        prop.save();
        prop = internalSetProperty(JCR_PREDECESSORS,
                new InternalValue[]{
                    InternalValue.create(new UUID(getBaseVersion().getUUID()))
                });
        prop.save();
    }

    /**
     * {@inheritDoc}
     */
    public void update(String srcWorkspaceName)
            throws NoSuchWorkspaceException, AccessDeniedException,
            LockException, InvalidItemStateException, RepositoryException {
        internalMerge(srcWorkspaceName, null, false);
    }

    /**
     * {@inheritDoc}
     */
    public NodeIterator merge(String srcWorkspace, boolean bestEffort)
            throws NoSuchWorkspaceException, AccessDeniedException,
            VersionException, LockException, InvalidItemStateException,
            RepositoryException {

        List failedIds = new ArrayList();
        internalMerge(srcWorkspace, failedIds, bestEffort);

        return new LazyItemIterator(itemMgr, failedIds);
    }

    public void cancelMerge(Version version)
            throws VersionException, InvalidItemStateException,
            UnsupportedRepositoryOperationException, RepositoryException {
        internalFinishMerge(version, true);
    }

    /**
     * {@inheritDoc}
     */
    public void doneMerge(Version version) throws VersionException,
            InvalidItemStateException, UnsupportedRepositoryOperationException,
            RepositoryException {
        internalFinishMerge(version, false);
    }

    /**
     * {@inheritDoc}
     */
    public boolean isCheckedOut() throws RepositoryException {
        // check state of this instance
        sanityCheck();

        return internalIsCheckedOut();
    }

    /**
     * {@inheritDoc}
     */
    public void restore(String versionName, boolean removeExisting)
            throws VersionException, ItemExistsException,
            UnsupportedRepositoryOperationException, LockException,
            InvalidItemStateException, RepositoryException {

        // checks
        sanityCheck();
        checkSessionHasPending();
        checkLock();

        GenericVersionSelector gvs = new GenericVersionSelector();
        gvs.setName(versionName);
        internalRestore(getVersionHistory().getVersion(versionName), gvs, removeExisting);
        // session.save/revert is done in internal restore
    }

    /**
     * {@inheritDoc}
     */
    public void restore(Version version, boolean removeExisting)
            throws VersionException, ItemExistsException,
            UnsupportedRepositoryOperationException, LockException,
            RepositoryException {

        // do checks
        sanityCheck();
        checkSessionHasPending();
        checkVersionable();
        checkLock();

        // check if 'own' version
        if (!version.getContainingHistory().isSame(getVersionHistory())) {
            throw new VersionException("Unable to restore version. Not same version history.");
        }

        internalRestore(version, new GenericVersionSelector(version.getCreated()), removeExisting);
        // session.save/revert is done in internal restore
    }

    /**
     * {@inheritDoc}
     */
    public void restore(Version version, String relPath, boolean removeExisting)
            throws PathNotFoundException, ItemExistsException, VersionException,
            ConstraintViolationException, UnsupportedRepositoryOperationException,
            LockException, InvalidItemStateException, RepositoryException {

        // do checks
        sanityCheck();
        checkSessionHasPending();
        checkLock();

        // if node exists, do a 'normal' restore
        if (hasNode(relPath)) {
            getNode(relPath).restore(version, removeExisting);
        } else {
            NodeImpl node;
            try {
                // check if versionable node exists
                InternalFrozenNode fn = ((VersionImpl) version).getFrozenNode();
                node = (NodeImpl) session.getNodeByUUID(fn.getFrozenUUID());
                if (removeExisting) {
                    try {
                        Path dstPath = Path.create(getPrimaryPath(), relPath, session.getNamespaceResolver(), true);
                        // move to respective location
                        session.move(node.getPath(), dstPath.toJCRPath(session.getNamespaceResolver()));
                        // need to refetch ?
                        node = (NodeImpl) session.getNodeByUUID(fn.getFrozenUUID());
                    } catch (MalformedPathException e) {
                        throw new RepositoryException(e);
                    } catch (NoPrefixDeclaredException e) {
                        throw new RepositoryException("InternalError.", e);
                    }
                } else {
                    throw new ItemExistsException("Unable to restore version. Versionable node already exists.");
                }
            } catch (ItemNotFoundException e) {
                // not found, create new one
                node = addNode(relPath, ((VersionImpl) version).getFrozenNode());
            }

            // recreate node from frozen state
            node.internalRestore(version, new GenericVersionSelector(version.getCreated()), removeExisting);
            // session.save/revert is done in internal restore
        }
    }

    /**
     * {@inheritDoc}
     */
    public void restoreByLabel(String versionLabel, boolean removeExisting)
            throws VersionException, ItemExistsException,
            UnsupportedRepositoryOperationException, LockException,
            InvalidItemStateException, RepositoryException {

        // do checks
        sanityCheck();
        checkSessionHasPending();
        checkLock();

        Version v = getVersionHistory().getVersionByLabel(versionLabel);
        if (v == null) {
            throw new VersionException("No version for label " + versionLabel + " found.");
        }
        internalRestore(v, new GenericVersionSelector(versionLabel), removeExisting);
        // session.save/revert is done in internal restore
    }

    /**
     * {@inheritDoc}
     */
    public VersionHistory getVersionHistory()
            throws UnsupportedRepositoryOperationException, RepositoryException {
        // check state of this instance
        sanityCheck();

        checkVersionable();
        return (VersionHistory) getProperty(JCR_VERSIONHISTORY).getNode();
    }

    /**
     * {@inheritDoc}
     */
    public Version getBaseVersion()
            throws UnsupportedRepositoryOperationException, RepositoryException {
        // check state of this instance
        sanityCheck();

        checkVersionable();
        return (Version) getProperty(JCR_BASEVERSION).getNode();
    }

    //-----------------------------------< versioning support: implementation >
    /**
     * Checks if this node is versionable, i.e. has 'mix:versionable'.
     *
     * @throws UnsupportedRepositoryOperationException
     *          if this node is not versionable
     */
    private void checkVersionable()
            throws UnsupportedRepositoryOperationException, RepositoryException {
        if (!isNodeType(MIX_VERSIONABLE)) {
            String msg = "Unable to perform versioning operation on non versionable node: " + safeGetJCRPath();
            log.debug(msg);
            throw new UnsupportedRepositoryOperationException(msg);
        }
    }

    /**
     * Checks if this nodes session has pending changes.
     *
     * @throws InvalidItemStateException if this nodes session has pending changes
     * @throws RepositoryException
     */
    private void checkSessionHasPending() throws RepositoryException {
        // check for pending changes
        if (session.hasPendingChanges()) {
            String msg = "Unable to perform operation. Session has pending changes.";
            log.debug(msg);
            throw new InvalidItemStateException(msg);
        }


    }

    /**
     * Returns the corresponding node in the workspace of the given session.
     * <p/>
     * Given a node N1 in workspace W1, its corresponding node N2 in workspace
     * W2 is defined as follows:
     * <ul>
     * <li>If N1 is the root node of W1 then N2 is the root node of W2.
     * <li>If N1 is referenceable (has a UUID) then N2 is the node in W2 with
     * the same UUID.
     * <li>If N1 is not referenceable (does not have a UUID) then there is some
     * node M1 which is either the nearest ancestor of N1 that is
     * referenceable, or is the root node of W1. If the corresponding node
     * of M1 is M2 in W2, then N2 is the node with the same relative path
     * from M2 as N1 has from M1.
     * </ul>
     *
     * @param srcSession
     * @return the corresponding node or <code>null</code> if no corresponding
     *         node exists.
     * @throws RepositoryException If another error occurs.
     */
    private NodeImpl getCorrespondingNode(SessionImpl srcSession)
            throws AccessDeniedException, RepositoryException {

        // search nearest ancestor that is referenceable
        NodeImpl m1 = this;
        while (!m1.isNodeType(MIX_REFERENCEABLE)) {
            if (m1.getDepth() == 0) {
                // root node
                try {
                    return (NodeImpl) srcSession.getItem(getPath());
                } catch (PathNotFoundException e) {
                    return null;
                }
            }
            m1 = (NodeImpl) m1.getParent();
        }

        try {
            // get corresponding ancestor (might throw ItemNotFoundException)
            NodeImpl m2 = (NodeImpl) srcSession.getNodeByUUID(m1.getUUID());

            // return m2, if m1 == n1
            if (m1 == this) {
                return m2;
            }

            String relPath;
            try {
                Path p = m1.getPrimaryPath().computeRelativePath(getPrimaryPath());
                // use prefix mappings of srcSession
                relPath = p.toJCRPath(srcSession.getNamespaceResolver());
            } catch (BaseException be) {
                // should never get here...
                String msg = "internal error: failed to determine relative path";
                log.error(msg, be);
                throw new RepositoryException(msg, be);
            }
            if (!m2.hasNode(relPath)) {
                return null;
            } else {
                return (NodeImpl) m2.getNode(relPath);
            }
        } catch (ItemNotFoundException e) {
            return null;
        }
    }

    /**
     * Performs the merge test. If the result is 'update', then the corresponding
     * source node is returned. if the result is 'leave' or 'besteffort-fail'
     * then <code>null</code> is returned. If the result of the merge test is
     * 'fail' with bestEffort set to <code>false</code> a MergeException is
     * thrown.
     *
     * @param srcSession
     * @param bestEffort
     * @return
     * @throws RepositoryException
     * @throws AccessDeniedException
     */
    private NodeImpl doMergeTest(SessionImpl srcSession, List failedIds, boolean bestEffort)
            throws RepositoryException, AccessDeniedException {

        // If N does not have a corresponding node then the merge result for N is leave.
        NodeImpl srcNode = getCorrespondingNode(srcSession);
        if (srcNode == null) {
            return null;
        }

        // if not versionable, update
        if (!isNodeType(MIX_VERSIONABLE) || failedIds == null) {
            return srcNode;
        }
        // if source node is not versionable, leave
        if (!srcNode.isNodeType(MIX_VERSIONABLE)) {
            return null;
        }
        // test versions
        InternalVersion v = ((VersionImpl) getBaseVersion()).getInternalVersion();
        InternalVersion vp = ((VersionImpl) srcNode.getBaseVersion()).getInternalVersion();
        if (vp.isMoreRecent(v) && !isCheckedOut()) {
            // I f V' is a successor (to any degree) of V, then the merge result for
            // N is update. This case can be thought of as the case where N' is
            // "newer" than N and therefore N should be updated to reflect N'.
            return srcNode;
        } else if (v.equals(vp) || v.isMoreRecent(vp)) {
            // If V' is a predecessor (to any degree) of V or if V and V' are
            // identical (i.e., are actually the same version), then the merge
            // result for N is leave. This case can be thought of as the case where
            // N' is "older" or the "same age" as N and therefore N should be left alone.
            return null;
        } else {
            // If V is neither a successor of, predecessor of, nor identical
            // with V', then the merge result for N is failed. This is the case
            // where N and N' represent divergent branches of the version graph,
            // thus determining the result of a merge is non-trivial.
            if (bestEffort) {
                // add 'offending' version to jcr:mergeFailed property
                Set set = internalGetMergeFailed();
                set.add(srcNode.getBaseVersion().getUUID());
                internalSetMergeFailed(set);
                failedIds.add(state.getId());
                return null;
            } else {
                String msg = "Unable to merge nodes. Violating versions. " + safeGetJCRPath();
                log.debug(msg);
                throw new MergeException(msg);
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    private void internalFinishMerge(Version version, boolean cancel)
            throws VersionException, InvalidItemStateException,
            UnsupportedRepositoryOperationException, RepositoryException {
        // check state of this instance
        sanityCheck();

        // check for pending changes
        if (hasPendingChanges()) {
            String msg = "Unable to finish merge. Node has pending changes: " + safeGetJCRPath();
            log.debug(msg);
            throw new InvalidItemStateException(msg);
        }

        // check versionable
        checkVersionable();

        // check lock
        checkLock();

        // check if checked out
        if (!internalIsCheckedOut()) {
            String msg = "Unable to finish merge. Node is checked-in: " + safeGetJCRPath();
            log.error(msg);
            throw new VersionException(msg);
        }

        // check if version is in mergeFailed list
        Set failed = internalGetMergeFailed();
        if (!failed.remove(version.getUUID())) {
            String msg = "Unable to finish merge. Specified version is not in jcr:mergeFailed property: " + safeGetJCRPath();
            log.error(msg);
            throw new VersionException(msg);
        }

        // remove version from mergeFailed list
        internalSetMergeFailed(failed);

        if (!cancel) {
            // add version to jcr:predecessors list
            Value[] vals = getProperty(JCR_PREDECESSORS).getValues();
            InternalValue[] v = new InternalValue[vals.length + 1];
            for (int i = 0; i < vals.length; i++) {
                v[i] = InternalValue.create(UUID.fromString(vals[i].getString()));
            }
            v[vals.length] = InternalValue.create(UUID.fromString(version.getUUID()));
            internalSetProperty(JCR_PREDECESSORS, v);
        }

        // save
        save();
    }

    /**
     * @return
     * @throws RepositoryException
     */
    private Set internalGetMergeFailed() throws RepositoryException {
        HashSet set = new HashSet();
        if (hasProperty(JCR_MERGEFAILED)) {
            Value[] vals = getProperty(JCR_MERGEFAILED).getValues();
            for (int i = 0; i < vals.length; i++) {
                set.add(vals[i].getString());
            }
        }
        return set;
    }

    /**
     * @param set
     * @throws RepositoryException
     */
    private void internalSetMergeFailed(Set set) throws RepositoryException {
        if (set.isEmpty()) {
            internalSetProperty(JCR_MERGEFAILED, (InternalValue[]) null);
        } else {
            InternalValue[] vals = new InternalValue[set.size()];
            Iterator iter = set.iterator();
            int i = 0;
            while (iter.hasNext()) {
                String uuid = (String) iter.next();
                vals[i++] = InternalValue.create(UUID.fromString(uuid));
            }
            internalSetProperty(JCR_MERGEFAILED, vals);
        }
    }

    /**
     * Determines the checked-out status of this node.
     * <p/>
     * A node is considered <i>checked-out</i> if it is versionable and
     * checked-out, or is non-versionable but its nearest versionable ancestor
     * is checked-out, or is non-versionable and there are no versionable
     * ancestors.
     *
     * @return a boolean
     * @see Node#isCheckedOut()
     */
    protected boolean internalIsCheckedOut() throws RepositoryException {
        /**
         * try shortcut first: 
         * if current node is 'new' we can safely consider it checked-out
         * since otherwise it would had been impossible to add it in the first
         * place
         */
        if (isNew()) {
            return true;
        }

        // search nearest ancestor that is versionable
        /**
         * FIXME should not only rely on existence of jcr:isCheckedOut property
         * but also verify that node.isNodeType("mix:versionable")==true;
         * this would have a negative impact on performance though...
         */
        NodeImpl node = this;
        while (!node.hasProperty(JCR_ISCHECKEDOUT)) {
            if (node.getDepth() == 0) {
                return true;
            }
            node = (NodeImpl) node.getParent();
        }
        return node.getProperty(JCR_ISCHECKEDOUT).getBoolean();
    }

    /**
     * Creates a new node at <code>relPath</code> of the node type, uuid and
     * eventual mixin types stored in the frozen node. The same as
     * <code>{@link #addNode(String relPath)}</code> except that the primary
     * node type type, the uuid and evt. mixin type of the new node is
     * explictly specified in the nt:frozen node.
     * <p/>
     *
     * @param name   The name of the new <code>Node</code> that is to be created.
     * @param frozen The frozen node that contains the creation information
     * @return The node that was added.
     * @throws ItemExistsException          If an item at the
     *                                      specified path already exists(and same-name siblings are not allowed).
     * @throws PathNotFoundException        If specified path implies intermediary
     *                                      <code>Node</code>s that do not exist.
     * @throws NoSuchNodeTypeException      If the specified <code>nodeTypeName</code>
     *                                      is not recognized.
     * @throws ConstraintViolationException If an attempt is made to add a node as the
     *                                      child of a <code>Property</code>
     * @throws RepositoryException          if another error occurs.
     */
    private NodeImpl addNode(QName name, InternalFrozenNode frozen)
            throws ItemExistsException, PathNotFoundException,
            ConstraintViolationException, NoSuchNodeTypeException,
            RepositoryException {

        // get frozen node type
        NodeTypeManagerImpl ntMgr = session.getNodeTypeManager();
        NodeTypeImpl nt = ntMgr.getNodeType(frozen.getFrozenPrimaryType());

        // get frozen uuid
        String uuid = frozen.getFrozenUUID();

        NodeImpl node = internalAddChildNode(name, nt, uuid);

        // get frozen mixin
        // todo: also respect mixing types on creation?
        QName[] mxNames = frozen.getFrozenMixinTypes();
        for (int i = 0; i < mxNames.length; i++) {
            node.addMixin(mxNames[i]);
        }
        return node;
    }

    /**
     * Creates a new node at <code>relPath</code> of the node type, uuid and
     * eventual mixin types stored in the frozen node. The same as
     * <code>{@link #addNode(String relPath)}</code> except that the primary
     * node type type, the uuid and evt. mixin type of the new node is
     * explictly specified in the nt:frozen node.
     * <p/>
     *
     * @param relPath The path of the new <code>Node</code> that is to be created.
     * @param frozen  The frozen node that contains the creation information
     * @return The node that was added.
     * @throws ItemExistsException          If an item at the
     *                                      specified path already exists(and same-name siblings are not allowed).
     * @throws PathNotFoundException        If specified path implies intermediary
     *                                      <code>Node</code>s that do not exist.
     * @throws NoSuchNodeTypeException      If the specified <code>nodeTypeName</code>
     *                                      is not recognized.
     * @throws ConstraintViolationException If an attempt is made to add a node as the
     *                                      child of a <code>Property</code>
     * @throws RepositoryException          if another error occurs.
     */
    private NodeImpl addNode(String relPath, InternalFrozenNode frozen)
            throws ItemExistsException, PathNotFoundException,
            ConstraintViolationException, NoSuchNodeTypeException,
            RepositoryException {

        // get frozen node type
        NodeTypeManagerImpl ntMgr = session.getNodeTypeManager();
        NodeTypeImpl nt = ntMgr.getNodeType(frozen.getFrozenPrimaryType());

        // get frozen uuid
        String uuid = frozen.getFrozenUUID();

        NodeImpl node = internalAddNode(relPath, nt, uuid);

        // get frozen mixin
        // todo: also respect mixing types on creation?
        QName[] mxNames = frozen.getFrozenMixinTypes();
        for (int i = 0; i < mxNames.length; i++) {
            node.addMixin(mxNames[i]);
        }
        return node;
    }

    /**
     * {@inheritDoc}
     */
    private void internalMerge(String srcWorkspaceName,
                               List failedIds, boolean bestEffort)
            throws NoSuchWorkspaceException, AccessDeniedException,
            LockException, InvalidItemStateException, RepositoryException {

        // might be added in future releases
        boolean removeExisting = true;
        boolean replaceExisting = false;

        // do checks
        sanityCheck();
        checkSessionHasPending();

        // if same workspace, ignore
        if (srcWorkspaceName.equals(session.getWorkspace().getName())) {
            return;
        }

        SessionImpl srcSession = null;
        try {
            // create session on other workspace for current subject
            // (may throw NoSuchWorkspaceException and AccessDeniedException)
            srcSession = rep.createSession(session.getSubject(), srcWorkspaceName);
            try {
                internalMerge(srcSession, failedIds, bestEffort, removeExisting, replaceExisting);
            } catch (RepositoryException e) {
                try {
                    session.refresh(false);
                } catch (RepositoryException e1) {
                    // ignore
                }
                throw e;
            }
            session.save();
        } finally {
            if (srcSession != null) {
                // we don't need the other session anymore, logout
                srcSession.logout();
            }
        }
    }

    /**
     * Merges/Updates this node with its corresponding ones
     *
     * @param srcSession
     * @param failedIds
     * @param bestEffort
     * @param removeExisting
     * @param replaceExisting
     * @throws LockException
     * @throws RepositoryException
     */
    private void internalMerge(SessionImpl srcSession, List failedIds, boolean bestEffort, boolean removeExisting, boolean replaceExisting)
            throws LockException, RepositoryException {

        NodeImpl srcNode = doMergeTest(srcSession, failedIds, bestEffort);
        if (srcNode == null) {
            // leave, iterate over children
            NodeIterator iter = getNodes();
            while (iter.hasNext()) {
                NodeImpl n = (NodeImpl) iter.nextNode();
                n.internalMerge(srcSession, failedIds, bestEffort, removeExisting, replaceExisting);
            }
            return;
        }

        // check lock status
        checkLock();

        // update the properties
        PropertyIterator iter = getProperties();
        while (iter.hasNext()) {
            PropertyImpl p = (PropertyImpl) iter.nextProperty();
            if (!srcNode.hasProperty(p.getQName())) {
                p.internalRemove(true);
            }
        }
        iter = srcNode.getProperties();
        while (iter.hasNext()) {
            PropertyImpl p = (PropertyImpl) iter.nextProperty();
            // ignore system types
            if (p.getQName().equals(JCR_PRIMARYTYPE)
                    || p.getQName().equals(JCR_MIXINTYPES)
                    || p.getQName().equals(JCR_UUID)) {
                continue;
            }
            if (p.getDefinition().isMultiple()) {
                internalSetProperty(p.getQName(), p.internalGetValues());
            } else {
                internalSetProperty(p.getQName(), p.internalGetValue());
            }
        }
        // todo: add/remove mixins ?

        // update the nodes. remove non existing ones
        NodeIterator niter = getNodes();
        while (niter.hasNext()) {
            NodeImpl n = (NodeImpl) niter.nextNode();
            // todo: does not work properly for samename siblings
            if (!srcNode.hasNode(n.getQName())) {
                n.internalRemove(true);
            }
        }

        // add source ones
        niter = srcNode.getNodes();
        while (niter.hasNext()) {
            NodeImpl child = (NodeImpl) niter.nextNode();
            NodeImpl dstNode = null;
            String uuid = child.internalGetUUID();
            if (hasNode(child.getQName())) {
                // todo: does not work properly for samename siblings
                dstNode = getNode(child.getQName());
            } else if (child.isNodeType(MIX_REFERENCEABLE)) {
                // if child is referenceable, check if correspondance exist in this workspace
                try {
                    dstNode = (NodeImpl) session.getNodeByUUID(uuid);
                    if (removeExisting) {
                        dstNode.internalRemove(false);
                        dstNode = null;
                    } else if (replaceExisting) {
                        // node exists outside of this update tree, so continue there
                    } else {
                        throw new ItemExistsException("Unable to update node: " + dstNode.safeGetJCRPath());
                    }
                } catch (ItemNotFoundException e) {
                    // does not exist
                }
            } else {
                // if child is not referenceable, clear uuid
                uuid = null;
            }
            if (dstNode == null) {
                dstNode = internalAddChildNode(child.getQName(), (NodeTypeImpl) child.getPrimaryNodeType(), uuid);
                // add mixins
                NodeType[] mixins = child.getMixinNodeTypes();
                for (int i = 0; i < mixins.length; i++) {
                    dstNode.addMixin(mixins[i].getName());
                }
                dstNode.internalMerge(srcSession, null, bestEffort, removeExisting, replaceExisting);
            } else {
                dstNode.internalMerge(srcSession, failedIds, bestEffort, removeExisting, replaceExisting);
            }
        }
    }

    /**
     * Internal method to restore a version.
     *
     * @param version
     * @param vsel    the version selector that will select the correct version for
     *                OPV=Version childnodes.
     * @throws UnsupportedRepositoryOperationException
     *
     * @throws RepositoryException
     */
    private void internalRestore(Version version, VersionSelector vsel, boolean removeExisting)
            throws UnsupportedRepositoryOperationException, RepositoryException {

        try {
            internalRestore(((VersionImpl) version).getInternalVersion(), vsel, removeExisting);
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
     * Internal method to restore a version.
     *
     * @param version
     * @param vsel           the version selector that will select the correct version for
     *                       OPV=Version childnodes.
     * @param removeExisting
     * @throws RepositoryException
     */
    protected InternalVersion[] internalRestore(InternalVersion version, VersionSelector vsel,
                                                boolean removeExisting)
            throws RepositoryException {

        // fail if root version
        if (version.isRootVersion()) {
            throw new VersionException("Restore of root version not allowed.");
        }

        // set jcr:isCheckedOut property to true, in order to avoid any conflicts
        internalSetProperty(JCR_ISCHECKEDOUT, InternalValue.create(true));

        // 1. The child node and properties of N will be changed, removed or
        //    added to, depending on their corresponding copies in V and their
        //    own OnParentVersion attributes (see 7.2.8, below, for details).
        HashSet restored = new HashSet();
        restoreFrozenState(version.getFrozenNode(), vsel, restored, removeExisting);
        restored.add(version);

        // 2. N's jcr:baseVersion property will be changed to point to V.
        internalSetProperty(JCR_BASEVERSION, InternalValue.create(new UUID(version.getId())));

        // 4. N's jcr:predecessor property is set to null
        internalSetProperty(JCR_PREDECESSORS, InternalValue.EMPTY_ARRAY, PropertyType.REFERENCE);

        // also clear mergeFailed
        internalSetProperty(JCR_MERGEFAILED, (InternalValue[]) null);

        // 3. N's jcr:isCheckedOut property is set to false.
        internalSetProperty(JCR_ISCHECKEDOUT, InternalValue.create(false));

        return (InternalVersion[]) restored.toArray(new InternalVersion[restored.size()]);
    }

    /**
     * Restores the properties and child nodes from the frozen state.
     *
     * @param freeze
     * @param vsel
     * @param removeExisting
     * @throws RepositoryException
     */
    void restoreFrozenState(InternalFrozenNode freeze, VersionSelector vsel, Set restored, boolean removeExisting)
            throws RepositoryException {

        // check uuid
        if (isNodeType(MIX_REFERENCEABLE)) {
            String uuid = freeze.getFrozenUUID();
            if (uuid != null && !uuid.equals(getUUID())) {
                throw new ItemExistsException("Unable to restore version of " + safeGetJCRPath() + ". UUID changed.");
            }
        }

        // check primary type
        if (!freeze.getFrozenPrimaryType().equals(nodeType.getQName())) {
            // todo: check with spec what should happen here
            throw new ItemExistsException("Unable to restore version of " + safeGetJCRPath() + ". PrimaryType changed.");
        }

        // adjust mixins
        QName[] mixinNames = freeze.getFrozenMixinTypes();
        setMixinTypesProperty(new HashSet(Arrays.asList(mixinNames)));

        // copy frozen properties
        PropertyState[] props = freeze.getFrozenProperties();
        HashSet propNames = new HashSet();
        for (int i = 0; i < props.length; i++) {
            PropertyState prop = props[i];
            propNames.add(prop.getName());
            if (prop.isMultiValued()) {
                internalSetProperty(props[i].getName(), prop.getValues());
            } else {
                internalSetProperty(props[i].getName(), prop.getValues()[0]);
            }
        }
        // remove properties that do not exist in the frozen representation
        PropertyIterator piter = getProperties();
        while (piter.hasNext()) {
            PropertyImpl prop = (PropertyImpl) piter.nextProperty();
            // ignore some props that are not well guarded by the OPV
            if (prop.getQName().equals(JCR_VERSIONHISTORY)) {
                continue;
            } else if (prop.getQName().equals(JCR_PREDECESSORS)) {
                continue;
            }
            if (prop.getDefinition().getOnParentVersion() == OnParentVersionAction.COPY
                    || prop.getDefinition().getOnParentVersion() == OnParentVersionAction.VERSION) {
                if (!propNames.contains(prop.getQName())) {
                    removeChildProperty(prop.getQName());
                }
            }
        }

        // add 'auto-create' properties that do not exist yet
        NodeTypeManagerImpl ntMgr = session.getNodeTypeManager();
        for (int j = 0; j < mixinNames.length; j++) {
            NodeTypeImpl mixin = ntMgr.getNodeType(mixinNames[j]);
            PropertyDefinition[] pda = mixin.getAutoCreatedPropertyDefinitions();
            for (int i = 0; i < pda.length; i++) {
                PropertyDefinitionImpl pd = (PropertyDefinitionImpl) pda[i];
                if (!hasProperty(pd.getQName())) {
                    createChildProperty(pd.getQName(), pd.getRequiredType(), pd);
                }
            }
        }

        // first delete all non frozen version histories
        NodeIterator iter = getNodes();
        while (iter.hasNext()) {
            NodeImpl n = (NodeImpl) iter.nextNode();
            if (!freeze.hasFrozenHistory(n.internalGetUUID())) {
                n.internalRemove(true);
            }
        }

        // restore the frozen nodes
        InternalFreeze[] frozenNodes = freeze.getFrozenChildNodes();
        for (int i = 0; i < frozenNodes.length; i++) {
            InternalFreeze child = frozenNodes[i];
            if (child instanceof InternalFrozenNode) {
                InternalFrozenNode f = (InternalFrozenNode) child;
                // check for existing
                if (f.getFrozenUUID() != null) {
                    try {
                        NodeImpl existing = (NodeImpl) session.getNodeByUUID(f.getFrozenUUID());
                        // check if one of this restoretrees node
                        if (removeExisting) {
                            existing.remove();
                        } else {
                            // since we delete the OPV=Copy children beforehand, all
                            // found nodes must be outside of this tree
                            throw new ItemExistsException("Unable to restore node, item already exists outside of restored tree: " + existing.safeGetJCRPath());
                        }
                    } catch (ItemNotFoundException e) {
                        // ignore, item with uuid does not exist
                    }
                }
                NodeImpl n = addNode(f.getName(), f);
                n.restoreFrozenState(f, vsel, restored, removeExisting);

            } else if (child instanceof InternalFrozenVersionHistory) {
                InternalFrozenVersionHistory f = (InternalFrozenVersionHistory) child;
                VersionHistoryImpl history = (VersionHistoryImpl) session.getNodeByUUID(f.getVersionHistoryId());
                String nodeId = history.getVersionableUUID();

                // check if representing versionable already exists somewhere
                if (itemMgr.itemExists(new NodeId(nodeId))) {
                    NodeImpl n = (NodeImpl) session.getNodeByUUID(nodeId);
                    if (n.getParent().isSame(this)) {
                        // so order at end
                        // orderBefore(n.getName(), "");
                    } else if (removeExisting) {
                        session.move(n.getPath(), getPath() + "/" + n.getName());
                    } else {
                        // since we delete the OPV=Copy children beforehand, all
                        // found nodes must be outside of this tree
                        throw new ItemExistsException("Unable to restore node, item already exists outside of restored tree: " + n.safeGetJCRPath());
                    }
                } else {
                    // get desired version from version selector
                    InternalVersion v = ((VersionImpl) vsel.select(history)).getInternalVersion();
                    NodeImpl node = addNode(child.getName(), v.getFrozenNode());
                    node.internalRestore(v, vsel, removeExisting);
                    // add this version to set
                    restored.add(v);
                }
            }
        }
    }

    /**
     * Copies a property to this node
     *
     * @param prop
     * @throws RepositoryException
     */
    protected void internalCopyPropertyFrom(PropertyImpl prop) throws RepositoryException {
        if (prop.getDefinition().isMultiple()) {
            Value[] values = prop.getValues();
            InternalValue[] ivalues = new InternalValue[values.length];
            for (int i = 0; i < values.length; i++) {
                ivalues[i] = InternalValue.create(values[i], session.getNamespaceResolver());
            }
            internalSetProperty(prop.getQName(), ivalues);
        } else {
            InternalValue value = InternalValue.create(prop.getValue(), session.getNamespaceResolver());
            internalSetProperty(prop.getQName(), value);
        }
    }

    //------------------------------------------------------< locking support >
    /**
     * {@inheritDoc}
     */
    public Lock lock(boolean isDeep, boolean isSessionScoped)
            throws UnsupportedRepositoryOperationException, LockException,
            AccessDeniedException, InvalidItemStateException,
            RepositoryException {
        // check state of this instance
        sanityCheck();

        // check for pending changes
        if (hasPendingChanges()) {
            String msg = "Unable to lock node. Node has pending changes: " + safeGetJCRPath();
            log.debug(msg);
            throw new InvalidItemStateException(msg);
        }

        checkLockable();

        LockManager lockMgr = ((WorkspaceImpl) session.getWorkspace()).getLockManager();
        return lockMgr.lock(this, isDeep, isSessionScoped);
    }

    /**
     * {@inheritDoc}
     */
    public Lock getLock()
            throws UnsupportedRepositoryOperationException, LockException,
            AccessDeniedException, RepositoryException {
        // check state of this instance
        sanityCheck();

        checkLockable();

        LockManager lockMgr = ((WorkspaceImpl) session.getWorkspace()).getLockManager();
        return lockMgr.getLock(this);
    }

    /**
     * {@inheritDoc}
     */
    public void unlock()
            throws UnsupportedRepositoryOperationException, LockException,
            AccessDeniedException, InvalidItemStateException,
            RepositoryException {
        // check state of this instance
        sanityCheck();

        // check for pending changes
        if (hasPendingChanges()) {
            String msg = "Unable to unlock node. Node has pending changes: " + safeGetJCRPath();
            log.debug(msg);
            throw new InvalidItemStateException(msg);
        }

        checkLockable();

        LockManager lockMgr = ((WorkspaceImpl) session.getWorkspace()).getLockManager();
        lockMgr.unlock(this);
    }

    /**
     * {@inheritDoc}
     */
    public boolean holdsLock() throws RepositoryException {
        // check state of this instance
        sanityCheck();

        if (!isNodeType(MIX_LOCKABLE)) {
            // a node that is not lockable never holds a lock
            return false;
        }

        LockManager lockMgr = ((WorkspaceImpl) session.getWorkspace()).getLockManager();
        return lockMgr.holdsLock(this);
    }

    /**
     * {@inheritDoc}
     */
    public boolean isLocked() throws RepositoryException {
        // check state of this instance
        sanityCheck();

        LockManager lockMgr = ((WorkspaceImpl) session.getWorkspace()).getLockManager();
        return lockMgr.isLocked(this);
    }

    /**
     * Checks if this node is lockable, i.e. has 'mix:lockable'.
     *
     * @throws UnsupportedRepositoryOperationException
     *                             if this node is not lockable
     * @throws RepositoryException if another error occurs
     */
    private void checkLockable()
            throws UnsupportedRepositoryOperationException, RepositoryException {
        if (!isNodeType(MIX_LOCKABLE)) {
            String msg = "Unable to perform locking operation on non-lockable node: " + safeGetJCRPath();
            log.debug(msg);
            throw new UnsupportedRepositoryOperationException(msg);
        }
    }

    /**
     * Check whether this node is locked by somebody else.
     *
     * @throws LockException       if this node is locked by somebody else
     * @throws RepositoryException if some other error occurs
     */
    protected void checkLock() throws LockException, RepositoryException {
        LockManager lockMgr = ((WorkspaceImpl) session.getWorkspace()).getLockManager();
        lockMgr.checkLock(this);
    }
}
