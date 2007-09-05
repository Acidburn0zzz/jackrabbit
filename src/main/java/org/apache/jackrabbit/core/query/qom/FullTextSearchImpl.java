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
package org.apache.jackrabbit.core.query.qom;

import org.apache.jackrabbit.name.QName;
import org.apache.jackrabbit.name.NamePathResolver;

import org.apache.jackrabbit.core.query.jsr283.qom.FullTextSearch;

/**
 * <code>FullTextSearchImpl</code>...
 */
public class FullTextSearchImpl
        extends ConstraintImpl
        implements FullTextSearch {

    /**
     * Name of the selector against which to apply this constraint
     */
    private final QName selectorName;

    /**
     * Name of the property.
     */
    private final QName propertyName;

    /**
     * Full text search expression.
     */
    private final String fullTextSearchExpression;

    FullTextSearchImpl(NamePathResolver resolver,
                       QName selectorName,
                       QName propertyName,
                       String fullTextSearchExpression) {
        super(resolver);
        this.selectorName = selectorName;
        this.propertyName = propertyName;
        this.fullTextSearchExpression = fullTextSearchExpression;
    }

    /**
     * Gets the name of the selector against which to apply this constraint.
     *
     * @return the selector name; non-null
     */
    public String getSelectorName() {
        return getJCRName(selectorName);
    }

    /**
     * Gets the name of the property.
     *
     * @return the property name if the full-text search scope is a property,
     *         otherwise null if the full-text search scope is the node (or node
     *         subtree, in some implementations).
     */
    public String getPropertyName() {
        return getJCRName(propertyName);
    }

    /**
     * Gets the full-text search expression.
     *
     * @return the full-text search expression; non-null
     */
    public String getFullTextSearchExpression() {
        return fullTextSearchExpression;
    }

    //------------------------< AbstractQOMNode >-------------------------------

    /**
     * Accepts a <code>visitor</code> and calls the appropriate visit method
     * depending on the type of this QOM node.
     *
     * @param visitor the visitor.
     */
    public void accept(QOMTreeVisitor visitor, Object data) {
        visitor.visit(this, data);
    }
}
