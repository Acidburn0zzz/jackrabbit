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

import org.apache.jackrabbit.name.QName;
import org.apache.jackrabbit.spi.QNodeTypeDefinition;
import org.apache.jackrabbit.spi.QNodeDefinition;
import org.apache.jackrabbit.spi.QPropertyDefinition;
import org.apache.jackrabbit.value.QValue;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;

import javax.jcr.RepositoryException;
import javax.jcr.NamespaceRegistry;
import javax.jcr.PropertyType;
import javax.jcr.nodetype.NoSuchNodeTypeException;

import java.util.Stack;
import java.util.Map;
import java.util.Collection;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.Iterator;
import java.io.InputStream;
import java.io.IOException;

/**
 * <code>DefinitionValidator</code>...
 */
// DIFF JR: class added (methods extracted from NodeTypeRegistry)
class DefinitionValidator {

    private static Logger log = LoggerFactory.getLogger(DefinitionValidator.class);

    private final NodeTypeRegistry ntRegistry;
    private final NamespaceRegistry nsRegistry;


    DefinitionValidator(NodeTypeRegistry ntRegistry, NamespaceRegistry nsRegistry) {
        this.ntRegistry = ntRegistry;
        this.nsRegistry = nsRegistry;
    }

    /**
     * Validate each QNodeTypeDefinition present in the given collection.
     *
     * @param ntDefs
     * @param validatedDefs
     * @param checkAutoCreatePropHasDefault flag used to disable checking that auto-created properties
     * have a default value; this check has to be disabled while validating
     * built-in node types because there are properties defined in built-in
     * node types which are auto-created but don't have a fixed default value
     * that can be exposed in a property definition because it is
     * system-generated (e.g. jcr:primaryType in nt:base).
     * // TODO FIXME
     * @return Map mapping the definition to the resulting effective nodetype
     * @throws InvalidNodeTypeDefException
     * @throws RepositoryException
     */
    public Map validateNodeTypeDefs(Collection ntDefs, Map validatedDefs, boolean checkAutoCreatePropHasDefault) throws InvalidNodeTypeDefException, RepositoryException {
        // tmp. map containing names/defs of validated nodetypes
        Map validDefs = new HashMap(validatedDefs);
        // map of nodetype definitions and effective nodetypes to be registered
        Map ntMap = new HashMap();
        ArrayList list = new ArrayList(ntDefs);

        // iterate over definitions until there are no more definitions with
        // unresolved (i.e. unregistered) dependencies or an error occurs;

        int count = -1;  // number of validated nt's per iteration
        while (list.size() > 0 && count != 0) {
            count = 0;
            Iterator iterator = list.iterator();
            while (iterator.hasNext()) {
                QNodeTypeDefinition ntd = (QNodeTypeDefinition) iterator.next();
                // check if definition has unresolved dependencies
                // DIFF JR: cannot compared to 'registered' nodetypes since registr. is performed later on
                if (validDefs.keySet().containsAll(ntd.getDependencies())) {
                    EffectiveNodeType ent = validateNodeTypeDef(ntd, validDefs, checkAutoCreatePropHasDefault);
                    // keep track of validated definitions and eff. nodetypes
                    if (!validDefs.containsKey(ntd.getQName())) {
                        validDefs.put(ntd.getQName(), ntd);
                    }
                    ntMap.put(ntd, ent);
                    // remove it from list
                    iterator.remove();
                    // increase count
                    count++;
                }
            }
        }
        if (list.size() > 0) {
            StringBuffer msg = new StringBuffer();
            msg.append("the following node types could not be registered because of unresolvable dependencies: ");
            Iterator iterator = list.iterator();
            while (iterator.hasNext()) {
                msg.append(((QNodeTypeDefinition) iterator.next()).getQName());
                msg.append(" ");
            }
            log.error(msg.toString());
            throw new InvalidNodeTypeDefException(msg.toString());
        }
        return ntMap;
    }

    /**
     *
     * @param ntDef
     * @param validatedDefs Map of qualified nodetype names and nodetype definitions
     * that are known to be valid or are already registered. This map is used to
     * validated dependencies and check for circular inheritance
     * @param checkAutoCreatePropHasDefault flag used to disable checking that auto-created properties
     * have a default value; this check has to be disabled while validating
     * built-in node types because there are properties defined in built-in
     * node types which are auto-created but don't have a fixed default value
     * that can be exposed in a property definition because it is
     * system-generated (e.g. jcr:primaryType in nt:base).
     * // TODO FIXME
     * @return
     * @throws InvalidNodeTypeDefException
     * @throws RepositoryException
     */
    public EffectiveNodeTypeImpl validateNodeTypeDef(QNodeTypeDefinition ntDef, Map validatedDefs, boolean checkAutoCreatePropHasDefault)
            throws InvalidNodeTypeDefException, RepositoryException {
        /**
         * the effective (i.e. merged and resolved) node type resulting from
         * the specified node type definition;
         * the effective node type will finally be created after the definition
         * has been verified and checked for conflicts etc.; in some cases it
         * will be created already at an earlier stage during the validation
         * of child node definitions
         */
        EffectiveNodeTypeImpl ent = null;

        QName name = ntDef.getQName();
        if (name == null) {
            String msg = "no name specified";
            log.debug(msg);
            throw new InvalidNodeTypeDefException(msg);
        }
        checkNamespace(name);

        // validate supertypes
        QName[] supertypes = ntDef.getSupertypes();
        if (supertypes != null && supertypes.length > 0) {
            for (int i = 0; i < supertypes.length; i++) {
                checkNamespace(supertypes[i]);
                /**
                 * simple check for infinite recursion
                 * (won't trap recursion on a deeper inheritance level)
                 */
                if (name.equals(supertypes[i])) {
                    String msg = "[" + name + "] invalid supertype: "
                            + supertypes[i] + " (infinite recursion))";
                    log.debug(msg);
                    throw new InvalidNodeTypeDefException(msg);
                }
                // DIFF JR: compare to given nt-name set and not to registered nodetypes
                if (!validatedDefs.containsKey(supertypes[i])) {
                    String msg = "[" + name + "] invalid supertype: "
                            + supertypes[i];
                    log.debug(msg);
                    throw new InvalidNodeTypeDefException(msg);
                }
            }

            /**
             * check for circularity in inheritance chain
             * ('a' extends 'b' extends 'a')
             */
            Stack inheritanceChain = new Stack();
            inheritanceChain.push(name);
            checkForCircularInheritance(supertypes, inheritanceChain, validatedDefs);
        }

        /**
         * note that infinite recursion through inheritance is automatically
         * being checked by the following call to getEffectiveNodeType()
         * as it's impossible to register a node type definition which
         * references a supertype that isn't registered yet...
         */

        /**
         * build effective (i.e. merged and resolved) node type from supertypes
         * and check for conflicts
         */
        if (supertypes != null && supertypes.length > 0) {
            try {
                // DIFF JR: use extra method that does not compare to registered nts
                EffectiveNodeType est = ntRegistry.getEffectiveNodeType(supertypes, validatedDefs);
                // make sure that all primary types except nt:base extend from nt:base
                if (!ntDef.isMixin() && !QName.NT_BASE.equals(ntDef.getQName())
                        && !est.includesNodeType(QName.NT_BASE)) {
                    String msg = "[" + name + "] all primary node types except"
                        + " nt:base itself must be (directly or indirectly) derived from nt:base";
                    log.debug(msg);
                    throw new InvalidNodeTypeDefException(msg);
                }
            } catch (NodeTypeConflictException ntce) {
                String msg = "[" + name + "] failed to validate supertypes";
                log.debug(msg);
                throw new InvalidNodeTypeDefException(msg, ntce);
            } catch (NoSuchNodeTypeException nsnte) {
                String msg = "[" + name + "] failed to validate supertypes";
                log.debug(msg);
                throw new InvalidNodeTypeDefException(msg, nsnte);
            }
        } else {
            // no supertypes specified: has to be either a mixin type or nt:base
            if (!ntDef.isMixin() && !QName.NT_BASE.equals(ntDef.getQName())) {
                String msg = "[" + name
                        + "] all primary node types except nt:base itself must be (directly or indirectly) derived from nt:base";
                log.debug(msg);
                throw new InvalidNodeTypeDefException(msg);
            }
        }

        checkNamespace(ntDef.getPrimaryItemName());

        // validate property definitions
        QPropertyDefinition[] pda = ntDef.getPropertyDefs();
        for (int i = 0; i < pda.length; i++) {
            QPropertyDefinition pd = pda[i];
            /**
             * sanity check:
             * make sure declaring node type matches name of node type definition
             */
            if (!name.equals(pd.getDeclaringNodeType())) {
                String msg = "[" + name + "#" + pd.getQName() + "] invalid declaring node type specified";
                log.debug(msg);
                throw new InvalidNodeTypeDefException(msg);
            }
            checkNamespace(pd.getQName());
            // check that auto-created properties specify a name
            if (pd.definesResidual() && pd.isAutoCreated()) {
                String msg = "[" + name + "#" + pd.getQName() + "] auto-created properties must specify a name";
                log.debug(msg);
                throw new InvalidNodeTypeDefException(msg);
            }
            // check that auto-created properties specify a type
            if (pd.getRequiredType() == PropertyType.UNDEFINED && pd.isAutoCreated()) {
                String msg = "[" + name + "#" + pd.getQName() + "] auto-created properties must specify a type";
                log.debug(msg);
                throw new InvalidNodeTypeDefException(msg);
            }
            /**
             * check default values:
             * make sure type of value is consistent with required property type
             */
            // DIFF JACKRABBIT: default internal values are built from the
            // required type, thus check for match with pd.getRequiredType is redundant
            QValue[] defVals = getQValues(pd);
            if (defVals == null || defVals.length == 0) {
                // no default values specified
                if (checkAutoCreatePropHasDefault) {
                    // auto-created properties must have a default value
                    if (pd.isAutoCreated()) {
                        String msg = "[" + name + "#" + pd.getQName() + "] auto-created property must have a default value";
                        log.debug(msg);
                        throw new InvalidNodeTypeDefException(msg);
                    }
                }
            }

            // check that default values satisfy value constraints
            ValueConstraint.checkValueConstraints(pd, defVals);

            /**
             * ReferenceConstraint:
             * the specified node type must be registered, with one notable
             * exception: the node type just being registered
             */
            String[] constraints = pd.getValueConstraints();
            if (constraints != null && constraints.length > 0) {

                if (pd.getRequiredType() == PropertyType.REFERENCE) {
                    for (int j = 0; j < constraints.length; j++) {
                        QName ntName = QName.valueOf(constraints[j]);
                        // DIFF JR: compare to given ntd map and not registered nts only
                        if (!name.equals(ntName) && !validatedDefs.containsKey(ntName)) {
                            String msg = "[" + name + "#" + pd.getQName()
                                    + "] invalid REFERENCE value constraint '"
                                    + ntName + "' (unknown node type)";
                            log.debug(msg);
                            throw new InvalidNodeTypeDefException(msg);
                        }
                    }
                }
            }
        }

        // validate child-node definitions
        QNodeDefinition[] cnda = ntDef.getChildNodeDefs();
        for (int i = 0; i < cnda.length; i++) {
            QNodeDefinition cnd = cnda[i];
            /**
             * sanity check:
             * make sure declaring node type matches name of node type definition
             */
            if (!name.equals(cnd.getDeclaringNodeType())) {
                String msg = "[" + name + "#" + cnd.getQName()
                        + "] invalid declaring node type specified";
                log.debug(msg);
                throw new InvalidNodeTypeDefException(msg);
            }
            checkNamespace(cnd.getQName());
            // check that auto-created child-nodes specify a name
            if (cnd.definesResidual() && cnd.isAutoCreated()) {
                String msg = "[" + name + "#" + cnd.getQName()
                        + "] auto-created child-nodes must specify a name";
                log.debug(msg);
                throw new InvalidNodeTypeDefException(msg);
            }
            // check that auto-created child-nodes specify a default primary type
            if (cnd.getDefaultPrimaryType() == null
                    && cnd.isAutoCreated()) {
                String msg = "[" + name + "#" + cnd.getQName()
                        + "] auto-created child-nodes must specify a default primary type";
                log.debug(msg);
                throw new InvalidNodeTypeDefException(msg);
            }
            // check default primary type
            QName dpt = cnd.getDefaultPrimaryType();
            checkNamespace(dpt);
            boolean referenceToSelf = false;
            EffectiveNodeType defaultENT = null;
            if (dpt != null) {
                // check if this node type specifies itself as default primary type
                if (name.equals(dpt)) {
                    referenceToSelf = true;
                }
                /**
                 * the default primary type must be registered, with one notable
                 * exception: the node type just being registered
                 */
                if (!name.equals(dpt) && !validatedDefs.containsKey(dpt)) {
                    String msg = "[" + name + "#" + cnd.getQName()
                            + "] invalid default primary type '" + dpt + "'";
                    log.debug(msg);
                    throw new InvalidNodeTypeDefException(msg);
                }
                /**
                 * build effective (i.e. merged and resolved) node type from
                 * default primary type and check for conflicts
                 */
                try {
                    if (!referenceToSelf) {
                        defaultENT = ntRegistry.getEffectiveNodeType(new QName[] {dpt}, validatedDefs);
                    } else {
                        /**
                         * the default primary type is identical with the node
                         * type just being registered; we have to instantiate it
                         * 'manually'
                         */
                        ent = EffectiveNodeTypeImpl.create(ntRegistry, ntDef, validatedDefs);
                        defaultENT = ent;
                    }
                    if (cnd.isAutoCreated()) {
                        /**
                         * check for circularity through default primary types
                         * of auto-created child nodes (node type 'a' defines
                         * auto-created child node with default primary type 'a')
                         */
                        Stack definingNTs = new Stack();
                        definingNTs.push(name);
                        checkForCircularNodeAutoCreation(defaultENT, definingNTs, validatedDefs);
                    }
                } catch (NodeTypeConflictException ntce) {
                    String msg = "[" + name + "#" + cnd.getQName()
                            + "] failed to validate default primary type";
                    log.debug(msg);
                    throw new InvalidNodeTypeDefException(msg, ntce);
                } catch (NoSuchNodeTypeException nsnte) {
                    String msg = "[" + name + "#" + cnd.getQName()
                            + "] failed to validate default primary type";
                    log.debug(msg);
                    throw new InvalidNodeTypeDefException(msg, nsnte);
                }
            }

            // check required primary types
            QName[] reqTypes = cnd.getRequiredPrimaryTypes();
            if (reqTypes != null && reqTypes.length > 0) {
                for (int n = 0; n < reqTypes.length; n++) {
                    QName rpt = reqTypes[n];
                    checkNamespace(rpt);
                    referenceToSelf = false;
                    /**
                     * check if this node type specifies itself as required
                     * primary type
                     */
                    if (name.equals(rpt)) {
                        referenceToSelf = true;
                    }
                    /**
                     * the required primary type must be registered, with one
                     * notable exception: the node type just being registered
                     */
                    if (!name.equals(rpt) && !validatedDefs.containsKey(rpt)) {
                        String msg = "[" + name + "#" + cnd.getQName()
                                + "] invalid required primary type: " + rpt;
                        log.debug(msg);
                        throw new InvalidNodeTypeDefException(msg);
                    }
                    /**
                     * check if default primary type satisfies the required
                     * primary type constraint
                     */
                    if (defaultENT != null && !defaultENT.includesNodeType(rpt)) {
                        String msg = "[" + name + "#" + cnd.getQName()
                                + "] default primary type does not satisfy required primary type constraint "
                                + rpt;
                        log.debug(msg);
                        throw new InvalidNodeTypeDefException(msg);
                    }
                    /**
                     * build effective (i.e. merged and resolved) node type from
                     * required primary type constraint and check for conflicts
                     */
                    try {
                        if (!referenceToSelf) {
                            ntRegistry.getEffectiveNodeType(new QName[] {rpt}, validatedDefs);
                        } else {
                            /**
                             * the required primary type is identical with the
                             * node type just being registered; we have to
                             * instantiate it 'manually'
                             */
                            if (ent == null) {
                                ent = EffectiveNodeTypeImpl.create(ntRegistry, ntDef, validatedDefs);
                            }
                        }
                    } catch (NodeTypeConflictException ntce) {
                        String msg = "[" + name + "#" + cnd.getQName()
                                + "] failed to validate required primary type constraint";
                        log.debug(msg);
                        throw new InvalidNodeTypeDefException(msg, ntce);
                    } catch (NoSuchNodeTypeException nsnte) {
                        String msg = "[" + name + "#" + cnd.getQName()
                                + "] failed to validate required primary type constraint";
                        log.debug(msg);
                        throw new InvalidNodeTypeDefException(msg, nsnte);
                    }
                }
            }
        }

        /**
         * now build effective (i.e. merged and resolved) node type from
         * this node type definition; this will potentially detect more
         * conflicts or problems
         */
        if (ent == null) {
            try {
                ent = EffectiveNodeTypeImpl.create(ntRegistry, ntDef, validatedDefs);
            } catch (NodeTypeConflictException ntce) {
                String msg = "[" + name + "] failed to resolve node type definition";
                log.debug(msg);
                throw new InvalidNodeTypeDefException(msg, ntce);
            } catch (NoSuchNodeTypeException nsnte) {
                String msg = "[" + name + "] failed to resolve node type definition";
                log.debug(msg);
                throw new InvalidNodeTypeDefException(msg, nsnte);
            }
        }
        return ent;
    }

    /**
     *
     * @param supertypes
     * @param inheritanceChain
     * @param ntdMap
     * @throws InvalidNodeTypeDefException
     * @throws RepositoryException
     */
    private void checkForCircularInheritance(QName[] supertypes, Stack inheritanceChain, Map ntdMap)
        throws InvalidNodeTypeDefException, RepositoryException {
        for (int i = 0; i < supertypes.length; i++) {
            QName stName = supertypes[i];
            int pos = inheritanceChain.lastIndexOf(stName);
            if (pos >= 0) {
                StringBuffer buf = new StringBuffer();
                for (int j = 0; j < inheritanceChain.size(); j++) {
                    if (j == pos) {
                        buf.append("--> ");
                    }
                    buf.append(inheritanceChain.get(j));
                    buf.append(" extends ");
                }
                buf.append("--> ");
                buf.append(stName);
                throw new InvalidNodeTypeDefException("circular inheritance detected: " + buf.toString());
            }

            if (ntdMap.containsKey(stName)) {
                QName[] sta = ((QNodeTypeDefinition)ntdMap.get(stName)).getSupertypes();
                if (sta != null && sta.length > 0) {
                    // check recursively
                    inheritanceChain.push(stName);
                    checkForCircularInheritance(sta, inheritanceChain, ntdMap);
                    inheritanceChain.pop();
                }
            } else {
                throw new InvalidNodeTypeDefException("Unknown supertype: " + stName);
            }
        }
    }

    /**
     *
     * @param childNodeENT
     * @param definingParentNTs
     * @param ntdMap
     * @throws InvalidNodeTypeDefException
     */
    private void checkForCircularNodeAutoCreation(EffectiveNodeType childNodeENT,
                                                  Stack definingParentNTs, Map ntdMap)
        throws InvalidNodeTypeDefException {
        // check for circularity through default node types of auto-created child nodes
        // (node type 'a' defines auto-created child node with default node type 'a')
        QName[] childNodeNTs = childNodeENT.getAllNodeTypes();
        for (int i = 0; i < childNodeNTs.length; i++) {
            QName nt = childNodeNTs[i];
            int pos = definingParentNTs.lastIndexOf(nt);
            if (pos >= 0) {
                StringBuffer buf = new StringBuffer();
                for (int j = 0; j < definingParentNTs.size(); j++) {
                    if (j == pos) {
                        buf.append("--> ");
                    }
                    buf.append("node type ");
                    buf.append(definingParentNTs.get(j));
                    buf.append(" defines auto-created child node with default ");
                }
                buf.append("--> ");
                buf.append("node type ");
                buf.append(nt);
                throw new InvalidNodeTypeDefException("circular node auto-creation detected: "
                    + buf.toString());
            }
        }

        QNodeDefinition[] nodeDefs = childNodeENT.getAutoCreateNodeDefs();
        for (int i = 0; i < nodeDefs.length; i++) {
            QName dnt = nodeDefs[i].getDefaultPrimaryType();
            QName definingNT = nodeDefs[i].getDeclaringNodeType();
            try {
                if (dnt != null) {
                    // check recursively
                    definingParentNTs.push(definingNT);
                    EffectiveNodeType ent = ntRegistry.getEffectiveNodeType(new QName[] {dnt}, ntdMap);
                    checkForCircularNodeAutoCreation(ent, definingParentNTs, ntdMap);
                    definingParentNTs.pop();
                }
            } catch (NoSuchNodeTypeException e) {
                String msg = definingNT + " defines invalid default node type for child node " + nodeDefs[i].getQName();
                log.debug(msg);
                throw new InvalidNodeTypeDefException(msg, e);
            } catch (NodeTypeConflictException e) {
                String msg = definingNT + " defines invalid default node type for child node " + nodeDefs[i].getQName();
                log.debug(msg);
                throw new InvalidNodeTypeDefException(msg, e);
            }
        }
    }

    /**
     * Utility method for verifying that the namespace of a <code>QName</code>
     * is registered; a <code>null</code> argument is silently ignored.
     * @param name name whose namespace is to be checked
     * @throws RepositoryException if the namespace of the given name is not
     *                             registered or if an unspecified error occured
     */
    private void checkNamespace(QName name) throws RepositoryException {
        if (name != null) {
            // make sure namespace uri denotes a registered namespace
            nsRegistry.getPrefix(name.getNamespaceURI());
        }
    }

    private static QValue[] getQValues(QPropertyDefinition propDef) throws RepositoryException {
        int reqType = propDef.getRequiredType();
        // if no default values are specified, need to return null.
        QValue[] ivs = null;
        if (reqType == PropertyType.BINARY) {
            InputStream[] dfv = propDef.getDefaultValuesAsStream();
            if (dfv != null) {
                ivs = new QValue[dfv.length];
                for (int i = 0; i < dfv.length; i++) {
                    try {
                        ivs[i] = QValue.create(dfv[i]);
                    } catch (IOException e) {
                        String msg = "[" + propDef.getQName() + "] error while reading binary default values.";
                        throw new RepositoryException(msg);
                    }
                }
            }
        } else {
            String[] dfv = propDef.getDefaultValues();
            if (dfv != null) {
                ivs = new QValue[dfv.length];
                if (reqType == PropertyType.UNDEFINED) {
                    reqType = PropertyType.STRING;
                }
                for (int i = 0; i < dfv.length; i++) {
                    ivs[i] = QValue.create(dfv[i], reqType);
                }
            }
        }
        return ivs;
    }
}