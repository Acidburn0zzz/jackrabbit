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
package org.apache.jackrabbit.core.nodetype;

import org.apache.jackrabbit.name.QName;

/**
 * <code>ItemDef</code> is the internal representation of
 * an item definition. It refers to <code>QName</code>s only
 * and is thus isolated from session-specific namespace mappings.
 *
 * @see javax.jcr.nodetype.ItemDefinition
 */
public interface ItemDef {

    public static final ItemDef[] EMPTY_ARRAY = new ItemDef[0];

    /**
     * The special wildcard name used as the name of residual item definitions.
     */
    public static final QName ANY_NAME = new QName("", "*");

    /**
     * Gets the name of the child item.
     *
     * @return the name of the child item.
     */
    QName getName();

    /**
     * Gets the name of the declaring node type.
     *
     * @return the name of the declaring node type.
     */
    QName getDeclaringNodeType();

    /**
     * Determines whether the item is 'autoCreated'.
     *
     * @return the 'autoCreated' flag.
     */
    boolean isAutoCreated();

    /**
     * Gets the 'onParentVersion' attribute of the item.
     *
     * @return the 'onParentVersion' attribute.
     */
    int getOnParentVersion();

    /**
     * Determines whether the item is 'protected'.
     *
     * @return the 'protected' flag.
     */
    boolean isProtected();

    /**
     * Determines whether the item is 'mandatory'.
     *
     * @return the 'mandatory' flag.
     */
    boolean isMandatory();

    /**
     * Determines whether this item definition defines a residual set of
     * child items. This is equivalent to calling
     * <code>getName().equals(ANY_NAME)</code>.
     *
     * @return <code>true</code> if this definition defines a residual set;
     *         <code>false</code> otherwise.
     */
    boolean definesResidual();

    /**
     * Determines whether this item definition defines a node.
     *
     * @return <code>true</code> if this is a node definition;
     *         <code>false</code> otherwise (i.e. it is a property definition).
     */
    boolean definesNode();
}
