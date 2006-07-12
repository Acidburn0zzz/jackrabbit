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
package org.apache.jackrabbit.jcr2spi.nodetype;

import org.apache.commons.collections.map.ReferenceMap;
import org.apache.jackrabbit.jcr2spi.util.Dumpable;
import org.apache.jackrabbit.name.QName;
import org.apache.jackrabbit.spi.QNodeDefinition;
import org.apache.jackrabbit.spi.QPropertyDefinition;
import org.apache.jackrabbit.spi.QNodeTypeDefinition;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;

import javax.jcr.NamespaceRegistry;
import javax.jcr.PropertyType;
import javax.jcr.RepositoryException;
import javax.jcr.nodetype.NoSuchNodeTypeException;
import javax.jcr.version.OnParentVersionAction;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * A <code>NodeTypeRegistry</code> ...
 */
public class NodeTypeRegistryImpl implements Dumpable, NodeTypeRegistry {

    private static Logger log = LoggerFactory.getLogger(NodeTypeRegistryImpl.class);

    // cache of pre-built aggregations of node types
    private final EffectiveNodeTypeCache entCache;

    // map of node type names and node type definitions
    private final HashMap registeredNTDefs;

    // definition of the root node
    private final QNodeDefinition rootNodeDef;

    // map of id's and property definitions
    private final Set propDefs;
    // map of id's and node definitions
    private final Set nodeDefs;

    /**
     * DIFF JACKRABBIT: persisting nodetypes is delegated to a separate object
     */
    private final NodeTypeStorage storage;

    /**
     * DIFF JACKRABBIT: validation is delegated to extra class
     */
    private final DefinitionValidator validator;


    // DIFF JR: checkAutoCreatePropHasDefault is set directely on calling validator.validate

    /**
     * Listeners (soft references)
     */
    private final Map listeners = Collections.synchronizedMap(new ReferenceMap(ReferenceMap.WEAK, ReferenceMap.WEAK));

    /**
     * Create a new <code>NodeTypeRegistry</codes>
     *
     * @param nodeTypeDefs
     * @param nsRegistry
     * @return <code>NodeTypeRegistry</codes> object
     * @throws RepositoryException
     */
    public static NodeTypeRegistryImpl create(Collection nodeTypeDefs, NodeTypeStorage storage, QNodeDefinition rootNodeDef, NamespaceRegistry nsRegistry)
            throws RepositoryException {
        NodeTypeRegistryImpl ntRegistry = new NodeTypeRegistryImpl(nodeTypeDefs, storage, rootNodeDef, nsRegistry);
        return ntRegistry;
    }

    /**
     * Private constructor
     *
     * @param nodeTypeDefs
     * @param nsRegistry
     * @throws RepositoryException
     */
    private NodeTypeRegistryImpl(Collection nodeTypeDefs, NodeTypeStorage storage, QNodeDefinition rootNodeDef, NamespaceRegistry nsRegistry)
            throws RepositoryException {
        this.storage = storage;
        this.validator = new DefinitionValidator(this, nsRegistry);

        entCache = new EffectiveNodeTypeCache();
        registeredNTDefs = new HashMap();

        propDefs = new HashSet();
        nodeDefs = new HashSet();

        // setup definition of root node
        this.rootNodeDef = rootNodeDef;
        nodeDefs.add(rootNodeDef);

        try {
            // validate & register the definitions
            // DIFF JACKRABBIT: we cannot determine built-in vs. custom nodetypes.
            // TODO: 'false' flag maybe not totally correct....
            Map defMap = validator.validateNodeTypeDefs(nodeTypeDefs, new HashMap(registeredNTDefs), false);
            internalRegister(defMap);
        } catch (InvalidNodeTypeDefException intde) {
            String error = "Unexpected error: Found invalid node type definition.";
            log.debug(error);
            throw new RepositoryException(error, intde);
        }
        // DIFF JR: 'finally' for resetting 'checkAutoCreated' not needed any more...
    }



    /**
     * @inheritDoc
     */
    public void addListener(NodeTypeRegistryListener listener) {
        if (!listeners.containsKey(listener)) {
            listeners.put(listener, listener);
        }
    }

    /**
     * @inheritDoc
     */
    public void removeListener(NodeTypeRegistryListener listener) {
        listeners.remove(listener);
    }

    /**
     * @inheritDoc
     */
    public synchronized QName[] getRegisteredNodeTypes() {
        return (QName[]) registeredNTDefs.keySet().toArray(new QName[registeredNTDefs.size()]);
    }


    /**
     * @inheritDoc
     */
    public synchronized boolean isRegistered(QName nodeTypeName) {
        return registeredNTDefs.containsKey(nodeTypeName);
    }

    /**
     * @inheritDoc
     */
    public QNodeDefinition getRootNodeDef() {
        return rootNodeDef;
    }

    /**
     * @inheritDoc
     */
    public synchronized EffectiveNodeType getEffectiveNodeType(QName ntName)
            throws NoSuchNodeTypeException {
        return getEffectiveNodeType(ntName, entCache, registeredNTDefs);
    }

    /**
     * @inheritDoc
     */
    public synchronized EffectiveNodeType getEffectiveNodeType(QName[] ntNames)
            throws NodeTypeConflictException, NoSuchNodeTypeException {
        return getEffectiveNodeType(ntNames, entCache, registeredNTDefs);
    }

    /**
     * @inheritDoc
     */
    public synchronized EffectiveNodeType getEffectiveNodeType(QName[] ntNames,
                                                               Map ntdMap)
        throws NodeTypeConflictException, NoSuchNodeTypeException {
        return getEffectiveNodeType(ntNames, entCache, ntdMap);
    }

    // DIFF JR: method private: EffectiveNodeTypeCache must not be exposed in interface
    /**
     *
     * @param ntName
     * @param anEntCache
     * @param aRegisteredNTDefCache
     * @return
     * @throws NoSuchNodeTypeException
     */
    private synchronized EffectiveNodeType getEffectiveNodeType(QName ntName,
                                                               EffectiveNodeTypeCache anEntCache,
                                                               Map aRegisteredNTDefCache)
            throws NoSuchNodeTypeException {
        // 1. check if effective node type has already been built
        EffectiveNodeType ent = anEntCache.get(new QName[]{ntName});
        if (ent != null) {
            return ent;
        }

        // 2. make sure that the specified node type exists
        if (!aRegisteredNTDefCache.containsKey(ntName)) {
            throw new NoSuchNodeTypeException(ntName.toString());
        }

        // 3. build effective node type
        try {
            QNodeTypeDefinition ntd = (QNodeTypeDefinition) aRegisteredNTDefCache.get(ntName);
            ent = EffectiveNodeTypeImpl.create(this, ntd, aRegisteredNTDefCache);
            // store new effective node type
            anEntCache.put(ent);
            return ent;
        } catch (NodeTypeConflictException ntce) {
            // should never get here as all registered node types have to be valid!
            String msg = "internal error: encountered invalid registered node type " + ntName;
            log.debug(msg);
            throw new NoSuchNodeTypeException(msg, ntce);
        }
    }

    // DIFF JR: method private: EffectiveNodeTypeCache must not be exposed in interface
    /**
     * @param ntNames
     * @param anEntCache
     * @param aRegisteredNTDefCache
     * @return
     * @throws NodeTypeConflictException
     * @throws NoSuchNodeTypeException
     */
    private synchronized EffectiveNodeType getEffectiveNodeType(QName[] ntNames,
                                                               EffectiveNodeTypeCache anEntCache,
                                                               Map aRegisteredNTDefCache)
            throws NodeTypeConflictException, NoSuchNodeTypeException {

        EffectiveNodeTypeCache.WeightedKey key =
                new EffectiveNodeTypeCache.WeightedKey(ntNames);

        // 1. check if aggregate has already been built
        if (anEntCache.contains(key)) {
            return anEntCache.get(key);
        }

        // 2. make sure every single node type exists
        for (int i = 0; i < ntNames.length; i++) {
            if (!aRegisteredNTDefCache.containsKey(ntNames[i])) {
                throw new NoSuchNodeTypeException(ntNames[i].toString());
            }
        }
        // 3. build aggregate
        EffectiveNodeTypeImpl result = null;

        // build list of 'best' existing sub-aggregates
        ArrayList tmpResults = new ArrayList();
        while (key.getNames().length > 0) {
            // check if we've already built this aggregate
            if (anEntCache.contains(key)) {
                tmpResults.add(anEntCache.get(key));
                // subtract the result from the temporary key
                // (which is 'empty' now)
                key = key.subtract(key);
                break;
            }
            /**
             * walk list of existing aggregates sorted by 'weight' of
             * aggregate (i.e. the cost of building it)
             */
            boolean foundSubResult = false;
            Iterator iter = anEntCache.keyIterator();
            while (iter.hasNext()) {
                EffectiveNodeTypeCache.WeightedKey k =
                        (EffectiveNodeTypeCache.WeightedKey) iter.next();
                /**
                 * check if the existing aggregate is a 'subset' of the one
                 * we're looking for
                 */
                if (key.contains(k)) {
                    tmpResults.add(anEntCache.get(k));
                    // subtract the result from the temporary key
                    key = key.subtract(k);
                    foundSubResult = true;
                    break;
                }
            }
            if (!foundSubResult) {
                /**
                 * no matching sub-aggregates found:
                 * build aggregate of remaining node types through iteration
                 */
                QName[] remainder = key.getNames();
                for (int i = 0; i < remainder.length; i++) {
                    QNodeTypeDefinition ntd = (QNodeTypeDefinition) aRegisteredNTDefCache.get(remainder[i]);
                    EffectiveNodeTypeImpl ent =
                            EffectiveNodeTypeImpl.create(this, ntd, aRegisteredNTDefCache);
                    // store new effective node type
                    anEntCache.put(ent);
                    if (result == null) {
                        result = ent;
                    } else {
                        result = result.merge(ent);
                        // store intermediate result (sub-aggregate)
                        anEntCache.put(result);
                    }
                }
                // add aggregate of remaining node types to result list
                tmpResults.add(result);
                break;
            }
        }
        // merge the sub-aggregates into new effective node type
        for (int i = 0; i < tmpResults.size(); i++) {
            if (result == null) {
                result = (EffectiveNodeTypeImpl) tmpResults.get(i);
            } else {
                result = result.merge((EffectiveNodeTypeImpl) tmpResults.get(i));
                // store intermediate result
                anEntCache.put(result);
            }
        }
        // we're done
        return result;
    }

    /**
     * @inheritDoc
     */
    public synchronized EffectiveNodeType registerNodeType(QNodeTypeDefinition ntDef)
            throws InvalidNodeTypeDefException, RepositoryException {
        // validate the new nodetype definition
        EffectiveNodeTypeImpl ent = validator.validateNodeTypeDef(ntDef, registeredNTDefs, true);

        // persist new node type definition
        storage.registerNodeTypes(new QNodeTypeDefinition[] {ntDef});

        // update internal caches
        internalRegister(ntDef, ent);

        // notify listeners
        notifyRegistered(ntDef.getQName());
        return ent;
    }

    /**
     * @inheritDoc
     */
    public synchronized void registerNodeTypes(Collection ntDefs)
            throws InvalidNodeTypeDefException, RepositoryException {

        // validate new nodetype definitions
        Map defMap = validator.validateNodeTypeDefs(ntDefs, registeredNTDefs, true);
        storage.registerNodeTypes((QNodeTypeDefinition[])ntDefs.toArray(new QNodeTypeDefinition[ntDefs.size()]));

        // update internal cache
        internalRegister(defMap);

        // notify listeners
        for (Iterator iter = ntDefs.iterator(); iter.hasNext();) {
            QName ntName = ((QNodeTypeDefinition)iter.next()).getQName();
            notifyRegistered(ntName);
        }
    }

    /**
     * @inheritDoc
     */
    public synchronized void unregisterNodeType(QName nodeTypeName)
            throws NoSuchNodeTypeException, RepositoryException {

        // perform basic validation
        if (!registeredNTDefs.containsKey(nodeTypeName)) {
            throw new NoSuchNodeTypeException(nodeTypeName.toString());
        }
        // DIFF JACKRABBIT: detection of built-in NodeTypes not possible
        // omit check for build-in nodetypes which would cause failure

        /**
         * check if there are node types that have dependencies on the given
         * node type
         */
        if (hasDependentNodeTypes(nodeTypeName)) {
            StringBuffer msg = new StringBuffer();
            msg.append(nodeTypeName + " could not be removed because registered node types are still referencing it.");
            throw new RepositoryException(msg.toString());
        }

        // make sure node type is not currently in use
        checkForReferencesInContent(nodeTypeName);

        // persist removal of node type definition
        storage.unregisterNodeTypes(new QName[] {nodeTypeName});

        // update internal cache
        internalUnregister(nodeTypeName);

        // notify listeners
        notifyUnregistered(nodeTypeName);
    }

    /**
     * @inheritDoc
     */
    public synchronized void unregisterNodeTypes(Collection nodeTypeNames)
            throws NoSuchNodeTypeException, RepositoryException {
        // do some preliminary checks
        for (Iterator iter = nodeTypeNames.iterator(); iter.hasNext();) {
            QName ntName = (QName) iter.next();
            if (!registeredNTDefs.containsKey(ntName)) {
                throw new NoSuchNodeTypeException(ntName.toString());
            }
            // DIFF JR: no distiction of built-in nts

            // check for node types other than those to be unregistered
            // that depend on the given node types
            Set dependents = getDependentNodeTypes(ntName);
            dependents.removeAll(nodeTypeNames);
            if (dependents.size() > 0) {
                StringBuffer msg = new StringBuffer();
                msg.append(ntName
                        + " can not be removed because the following node types depend on it: ");
                for (Iterator depIter = dependents.iterator(); depIter.hasNext();) {
                    msg.append(depIter.next());
                    msg.append(" ");
                }
                throw new RepositoryException(msg.toString());
            }
        }

        // make sure node types are not currently in use
        for (Iterator iter = nodeTypeNames.iterator(); iter.hasNext();) {
            QName ntName = (QName) iter.next();
            checkForReferencesInContent(ntName);
        }



        // persist removal of node type definitions
        storage.unregisterNodeTypes((QName[]) nodeTypeNames.toArray(new QName[nodeTypeNames.size()]));


        // all preconditions are met, node types can now safely be unregistered
        internalUnregister(nodeTypeNames);

        // notify listeners
        for (Iterator iter = nodeTypeNames.iterator(); iter.hasNext();) {
            QName ntName = (QName) iter.next();
            notifyUnregistered(ntName);
        }
    }

    /**
     * @inheritDoc
     */
    public synchronized EffectiveNodeType reregisterNodeType(QNodeTypeDefinition ntd)
            throws NoSuchNodeTypeException, InvalidNodeTypeDefException,
            RepositoryException {
        QName name = ntd.getQName();
        if (!registeredNTDefs.containsKey(name)) {
            throw new NoSuchNodeTypeException(name.toString());
        }
        // DIFF JACKRABBIT: detection of built-in NodeTypes not possible
        // omit check for build-in nodetypes which would cause failure

        /**
         * validate new node type definition
         */
        EffectiveNodeTypeImpl ent = validator.validateNodeTypeDef(ntd, registeredNTDefs, true);

        // DIFF JACKRABBIT: removed check for severity of nt modification

        // first call reregistering on storage
        storage.reregisterNodeTypes(new QNodeTypeDefinition[]{ntd});

        // unregister old node type definition
        internalUnregister(name);
        // register new definition
        internalRegister(ntd, ent);

        // notify listeners
        notifyReRegistered(name);
        return ent;
    }

    /**
     * @inheritDoc
     */
    public synchronized QNodeTypeDefinition getNodeTypeDefinition(QName nodeTypeName)
            throws NoSuchNodeTypeException {
        if (!registeredNTDefs.containsKey(nodeTypeName)) {
            throw new NoSuchNodeTypeException(nodeTypeName.toString());
        }
        QNodeTypeDefinition def = (QNodeTypeDefinition) registeredNTDefs.get(nodeTypeName);
        return def;
    }

    //------------------------------------------------------------< private >---
    /**
     * Notify the listeners that a node type <code>ntName</code> has been registered.
     */
    private void notifyRegistered(QName ntName) {
        // copy listeners to array to avoid ConcurrentModificationException
        NodeTypeRegistryListener[] la =
                new NodeTypeRegistryListener[listeners.size()];
        Iterator iter = listeners.values().iterator();
        int cnt = 0;
        while (iter.hasNext()) {
            la[cnt++] = (NodeTypeRegistryListener) iter.next();
        }
        for (int i = 0; i < la.length; i++) {
            if (la[i] != null) {
                la[i].nodeTypeRegistered(ntName);
            }
        }
    }

    /**
     * Notify the listeners that a node type <code>ntName</code> has been re-registered.
     */
    private void notifyReRegistered(QName ntName) {
        // copy listeners to array to avoid ConcurrentModificationException
        NodeTypeRegistryListener[] la = new NodeTypeRegistryListener[listeners.size()];
        Iterator iter = listeners.values().iterator();
        int cnt = 0;
        while (iter.hasNext()) {
            la[cnt++] = (NodeTypeRegistryListener) iter.next();
        }
        for (int i = 0; i < la.length; i++) {
            if (la[i] != null) {
                la[i].nodeTypeReRegistered(ntName);
            }
        }
    }

    /**
     * Notify the listeners that a node type <code>ntName</code> has been unregistered.
     */
    private void notifyUnregistered(QName ntName) {
        // copy listeners to array to avoid ConcurrentModificationException
        NodeTypeRegistryListener[] la = new NodeTypeRegistryListener[listeners.size()];
        Iterator iter = listeners.values().iterator();
        int cnt = 0;
        while (iter.hasNext()) {
            la[cnt++] = (NodeTypeRegistryListener) iter.next();
        }
        for (int i = 0; i < la.length; i++) {
            if (la[i] != null) {
                la[i].nodeTypeUnregistered(ntName);
            }
        }
    }

    private void internalRegister(Map defMap) {
        Iterator it = defMap.keySet().iterator();
        while (it.hasNext()) {
            QNodeTypeDefinition ntd = (QNodeTypeDefinition)it.next();
            internalRegister(ntd, (EffectiveNodeTypeImpl)defMap.get(ntd));
        }
    }

    private void internalRegister(QNodeTypeDefinition ntd, EffectiveNodeTypeImpl ent) {

        // store new effective node type instance
        entCache.put(ent);
        // register nt-definition
        registeredNTDefs.put(ntd.getQName(), ntd);

        // store property & child node definitions of new node type by id
        QPropertyDefinition[] pda = ntd.getPropertyDefs();
        for (int i = 0; i < pda.length; i++) {
            propDefs.add(pda[i]);
        }
        QNodeDefinition[] nda = ntd.getChildNodeDefs();
        for (int i = 0; i < nda.length; i++) {
            nodeDefs.add(nda[i]);
        }
    }

    private void internalUnregister(QName name) {
        // DIFF JACKRABBIT: check for registered name removed, since duplicate code

        // DIFF JACKRABBIT: detection of built-in NodeTypes not possible
        // omit check for build-in nodetypes which would cause failure

        QNodeTypeDefinition ntd = (QNodeTypeDefinition) registeredNTDefs.get(name);
        registeredNTDefs.remove(name);
        /**
         * remove all affected effective node types from aggregates cache
         * (copy keys first to prevent ConcurrentModificationException)
         */
        ArrayList keys = new ArrayList(entCache.keySet());
        for (Iterator keysIter = keys.iterator(); keysIter.hasNext();) {
            EffectiveNodeTypeCache.WeightedKey k =
                    (EffectiveNodeTypeCache.WeightedKey) keysIter.next();
            EffectiveNodeType ent = entCache.get(k);
            if (ent.includesNodeType(name)) {
                entCache.remove(k);
            }
        }

        // remove property & child node definitions
        QPropertyDefinition[] pda = ntd.getPropertyDefs();
        for (int i = 0; i < pda.length; i++) {
            propDefs.remove(pda[i]);
        }
        QNodeDefinition[] nda = ntd.getChildNodeDefs();
        for (int i = 0; i < nda.length; i++) {
            nodeDefs.remove(nda[i]);
        }
    }

    private void internalUnregister(Collection ntNames) {
        for (Iterator iter = ntNames.iterator(); iter.hasNext();) {
            QName name = (QName) iter.next();
            internalUnregister(name);
        }
    }

    /**
     * Returns the names of those registered node types that have
     * dependencies on the given node type.
     *
     * @param nodeTypeName
     * @return a set of node type <code>QName</code>s
     * @throws NoSuchNodeTypeException
     */
    private synchronized boolean hasDependentNodeTypes(QName nodeTypeName)
            throws NoSuchNodeTypeException {
        if (!registeredNTDefs.containsKey(nodeTypeName)) {
            throw new NoSuchNodeTypeException(nodeTypeName.toString());
        }
        Iterator iter = registeredNTDefs.values().iterator();
        while (iter.hasNext()) {
            QNodeTypeDefinition ntd = (QNodeTypeDefinition) iter.next();
            if (ntd.getDependencies().contains(nodeTypeName)) {
                return true;
            }
        }
        return false;
    }

   /**
     * Returns the names of those registered node types that have
     * dependencies on the given node type.
     *
     * @param nodeTypeName node type name
     * @return a set of node type <code>QName</code>s
     * @throws NoSuchNodeTypeException
     */
    private synchronized Set getDependentNodeTypes(QName nodeTypeName)
            throws NoSuchNodeTypeException {
        if (!registeredNTDefs.containsKey(nodeTypeName)) {
            throw new NoSuchNodeTypeException(nodeTypeName.toString());
        }

        /**
         * collect names of those node types that have dependencies on the given
         * node type
         */
        HashSet names = new HashSet();
        Iterator iter = registeredNTDefs.values().iterator();
        while (iter.hasNext()) {
            QNodeTypeDefinition ntd = (QNodeTypeDefinition) iter.next();
            if (ntd.getDependencies().contains(nodeTypeName)) {
                names.add(ntd.getQName());
            }
        }
        return names;
    }

    /**
     * Checks whether there is existing content that would conflict with the
     * given node type definition.
     * <p/>
     * This method is not implemented yet and always throws a
     * <code>RepositoryException</code>.
     * <p/>
     * TODO
     * <ol>
     * <li>apply deep locks on root nodes in every workspace or alternatively
     * put repository in 'exclusive' or 'single-user' mode
     * <li>check if the given node type (or any node type that has
     * dependencies on this node type) is currently referenced by nodes
     * in the repository.
     * <li>check if applying the changed definitions to the affected items would
     * violate existing node type constraints
     * <li>apply and persist changes to affected nodes (e.g. update
     * definition id's, etc.)
     * </ul>
     * <p/>
     * the above checks/actions are absolutely necessary in order to
     * guarantee integrity of repository content.
     *
     * @param ntd The node type definition replacing the former node type
     *            definition of the same name.
     * @throws RepositoryException If there is conflicting content or if the
     *                             check failed for some other reason.
     */
    private void checkForConflictingContent(QNodeTypeDefinition ntd)
            throws RepositoryException {
        /**
         * collect names of node types that have dependencies on the given
         * node type
         */
        //Set dependentNTs = getDependentNodeTypes(ntd.getQName());

        throw new RepositoryException("not yet implemented");
    }

    /**
     * Checks whether there is existing content that directly or indirectly
     * refers to the specified node type.
     * <p/>
     * This method is not implemented yet and always throws a
     * <code>RepositoryException</code>.
     * <p/>
     * TODO:
     * <ol>
     * <li>apply deep locks on root nodes in every workspace or alternatively
     * put repository in 'single-user' mode
     * <li>check if the given node type is currently referenced by nodes
     * in the repository.
     * <li>remove the node type if it is not currently referenced, otherwise
     * throw exception
     * </ul>
     * <p/>
     * the above checks are absolutely necessary in order to guarantee
     * integrity of repository content.
     *
     * @param nodeTypeName The name of the node type to be checked.
     * @throws RepositoryException If the specified node type is currently
     *                             being referenced or if the check failed for
     *                             some other reason.
     */
    private void checkForReferencesInContent(QName nodeTypeName)
            throws RepositoryException {
        throw new RepositoryException("not yet implemented");
    }
    //-----------------------------------------------------------< Dumpable >---
    /**
     * {@inheritDoc}
     */
    public void dump(PrintStream ps) {
        ps.println("NodeTypeRegistry (" + this + ")");
        ps.println();
        ps.println("Registered NodeTypes:");
        ps.println();
        Iterator iter = registeredNTDefs.values().iterator();
        while (iter.hasNext()) {
            QNodeTypeDefinition ntd = (QNodeTypeDefinition) iter.next();
            ps.println(ntd.getQName());
            QName[] supertypes = ntd.getSupertypes();
            ps.println("\tSupertypes");
            for (int i = 0; i < supertypes.length; i++) {
                ps.println("\t\t" + supertypes[i]);
            }
            ps.println("\tMixin\t" + ntd.isMixin());
            ps.println("\tOrderableChildNodes\t" + ntd.hasOrderableChildNodes());
            ps.println("\tPrimaryItemName\t" + (ntd.getPrimaryItemName() == null ? "<null>" : ntd.getPrimaryItemName().toString()));
            QPropertyDefinition[] pd = ntd.getPropertyDefs();
            for (int i = 0; i < pd.length; i++) {
                ps.print("\tPropertyDefinition");
                ps.println(" (declared in " + pd[i].getDeclaringNodeType() + ") ");
                ps.println("\t\tName\t\t" + (pd[i].definesResidual() ? "*" : pd[i].getQName().toString()));
                String type = pd[i].getRequiredType() == 0 ? "null" : PropertyType.nameFromValue(pd[i].getRequiredType());
                ps.println("\t\tRequiredType\t" + type);
                String[] vca = pd[i].getValueConstraints();
                StringBuffer constraints = new StringBuffer();
                if (vca == null) {
                    constraints.append("<null>");
                } else {
                    for (int n = 0; n < vca.length; n++) {
                        if (constraints.length() > 0) {
                            constraints.append(", ");
                        }
                        constraints.append(vca[n]);
                    }
                }
                ps.println("\t\tValueConstraints\t" + constraints.toString());
                String[] defVals = pd[i].getDefaultValues();
                StringBuffer defaultValues = new StringBuffer();
                if (defVals == null) {
                    defaultValues.append("<null>");
                } else {
                    for (int n = 0; n < defVals.length; n++) {
                        if (defaultValues.length() > 0) {
                            defaultValues.append(", ");
                        }
                        defaultValues.append(defVals[n]);
                    }
                }
                ps.println("\t\tDefaultValue\t" + defaultValues.toString());
                ps.println("\t\tAutoCreated\t" + pd[i].isAutoCreated());
                ps.println("\t\tMandatory\t" + pd[i].isMandatory());
                ps.println("\t\tOnVersion\t" + OnParentVersionAction.nameFromValue(pd[i].getOnParentVersion()));
                ps.println("\t\tProtected\t" + pd[i].isProtected());
                ps.println("\t\tMultiple\t" + pd[i].isMultiple());
            }
            QNodeDefinition[] nd = ntd.getChildNodeDefs();
            for (int i = 0; i < nd.length; i++) {
                ps.print("\tNodeDefinition");
                ps.println(" (declared in " + nd[i].getDeclaringNodeType() + ") ");
                ps.println("\t\tName\t\t" + (nd[i].definesResidual() ? "*" : nd[i].getQName().toString()));
                QName[] reqPrimaryTypes = nd[i].getRequiredPrimaryTypes();
                if (reqPrimaryTypes != null && reqPrimaryTypes.length > 0) {
                    for (int n = 0; n < reqPrimaryTypes.length; n++) {
                        ps.print("\t\tRequiredPrimaryType\t" + reqPrimaryTypes[n]);
                    }
                }
                QName defPrimaryType = nd[i].getDefaultPrimaryType();
                if (defPrimaryType != null) {
                    ps.print("\n\t\tDefaultPrimaryType\t" + defPrimaryType);
                }
                ps.println("\n\t\tAutoCreated\t" + nd[i].isAutoCreated());
                ps.println("\t\tMandatory\t" + nd[i].isMandatory());
                ps.println("\t\tOnVersion\t" + OnParentVersionAction.nameFromValue(nd[i].getOnParentVersion()));
                ps.println("\t\tProtected\t" + nd[i].isProtected());
                ps.println("\t\tAllowsSameNameSiblings\t" + nd[i].allowsSameNameSiblings());
            }
        }
        ps.println();

        entCache.dump(ps);
    }
}
