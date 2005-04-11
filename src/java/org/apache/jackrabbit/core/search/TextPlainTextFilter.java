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
package org.apache.jackrabbit.core.search;

import org.apache.jackrabbit.core.state.PropertyState;
import org.apache.jackrabbit.core.InternalValue;
import org.apache.jackrabbit.core.BLOBFileValue;
import org.apache.jackrabbit.core.search.lucene.FieldNames;

import javax.jcr.RepositoryException;
import java.util.Map;
import java.util.HashMap;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.io.Reader;

/**
 * Implements a {@link TextFilter} that handles binary properties of mime-type
 * text/plain.
 */
public class TextPlainTextFilter implements TextFilter {

    /**
     * Returns <code>true</code> for <code>text/plain</code>; <code>false</code>
     * in all other cases.
     * @param mimeType the mime-type.
     * @return <code>true</code> for <code>text/plain</code>; <code>false</code>
     * in all other cases.
     */
    public boolean canFilter(String mimeType) {
        return "text/plain".equalsIgnoreCase(mimeType);
    }

    /**
     * Returns a map with a single entry for field {@link FieldNames#FULLTEXT}.
     * @param data the data property.
     * @param encoding the encoding
     * @return a map with a single Reader value for field
     *  {@link FieldNames#FULLTEXT}.
     * @throws RepositoryException if encoding is not supported or data is a
     *  multi-value property.
     */
    public Map doFilter(PropertyState data, String encoding) throws RepositoryException {
        InternalValue[] values = data.getValues();
        if (values.length > 0) {
            BLOBFileValue blob = (BLOBFileValue) values[0].internalValue();
            try {
                Reader reader;
                if (encoding == null) {
                    // use platform default
                    reader = new InputStreamReader(blob.getStream());
                } else {
                    reader = new InputStreamReader(blob.getStream(), encoding);
                }
                Map result = new HashMap();
                result.put(FieldNames.FULLTEXT, reader);
                return result;
            } catch (UnsupportedEncodingException e) {
                throw new RepositoryException(e);
            }
        } else {
            // multi value not supported
            throw new RepositoryException("Multi-valued binary properties not supported.");
        }
    }
}
