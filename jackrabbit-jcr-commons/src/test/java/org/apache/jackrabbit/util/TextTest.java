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
package org.apache.jackrabbit.util;

import junit.framework.TestCase;
import org.apache.jackrabbit.name.IllegalNameException;
import org.apache.jackrabbit.name.NameFormat;

import java.util.List;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Map;
import java.util.HashMap;

/**
 * Test cases for the Text utility class.
 */
public class TextTest extends TestCase {

    private void checkEscape(String name) {
        String escaped = Text.escapeIllegalJcrChars(name);
        try {
            NameFormat.checkFormat(escaped);
        } catch (IllegalNameException e) {
            fail("Illegal name: " + escaped);
        }
        assertEquals(name, Text.unescapeIllegalJcrChars(escaped));
    }

    public void testEscape() {
        // Handling of normal names
        checkEscape("foobar");
        // Handling of short names
        checkEscape("x");
        checkEscape("xx");
        checkEscape("xxx");
        // Handling of spaces
        checkEscape("x x");
        checkEscape("x x x");
        checkEscape(" ");
        checkEscape(" x");
        checkEscape("x ");
        checkEscape("  ");
        checkEscape("   ");
        checkEscape(" x ");
        // Handling of dots within the first two characters
        checkEscape(".");
        checkEscape("..");
        checkEscape(".x");
        checkEscape("x.");
        checkEscape(". ");
        checkEscape(" .");
        // Handling of the special characters
        checkEscape("%/:[]*'\"|\t\r\n");
    }

    public void testIsDescendant() {
        String parent = "/";
        List descendants = new ArrayList();
        descendants.add("/a");
        descendants.add("/a/b");
        for (Iterator it = descendants.iterator(); it.hasNext();) {
            String desc = it.next().toString();
            assertTrue(desc + " must be descendant of " + parent, Text.isDescendant(parent, desc));
        }
        List nonDescendants = new ArrayList();
        nonDescendants.add("/");
        nonDescendants.add("a");
        for (Iterator it = nonDescendants.iterator(); it.hasNext();) {
            String nonDesc = it.next().toString();
            assertFalse(nonDesc + " isn't a descendant of " + parent,Text.isDescendant(parent, nonDesc));
        }

        parent = "/a/b";
        descendants = new ArrayList();
        descendants.add("/a/b/c");
        descendants.add("/a/b/c/");
        for (Iterator it = descendants.iterator(); it.hasNext();) {
            String desc = it.next().toString();
            assertTrue(desc + " must be descendant of " + parent, Text.isDescendant(parent, desc));
        }
        nonDescendants = new ArrayList();
        nonDescendants.add("/");
        nonDescendants.add("/a");
        nonDescendants.add("/a/b");
        nonDescendants.add("/a/b/");
        nonDescendants.add("/d");
        nonDescendants.add("/d/b");
        for (Iterator it = nonDescendants.iterator(); it.hasNext();) {
            String nonDesc = it.next().toString();
            assertFalse(nonDesc + " isn't a descendant of " + parent, Text.isDescendant(parent, nonDesc));
        }
    }

    public void testGetName() {
        List l = new ArrayList();
        l.add(new String[] {"/a/b/c", "c"});
        l.add(new String[] {"c", "c"});
        l.add(new String[] {null, null});
        l.add(new String[] {"", ""});
        l.add(new String[] {"/", ""});
        l.add(new String[] {"http://jackrabbit.apache.org/jackrabbit", "jackrabbit"});
        l.add(new String[] {"http://jackrabbit.apache.org/jackrabbit/", ""});

        for (Iterator it = l.iterator(); it.hasNext();) {
            String[] strs = (String[]) it.next();
            assertEquals(strs[1], Text.getName(strs[0]));
        }

        // Text.getName(String path, boolean ignoreTrailingSlash)
        l = new ArrayList();
        l.add(new String[] {"http://jackrabbit.apache.org/jackrabbit", "jackrabbit"});
        l.add(new String[] {"http://jackrabbit.apache.org/jackrabbit/", "jackrabbit"});

        for (Iterator it = l.iterator(); it.hasNext();) {
            String[] strs = (String[]) it.next();
            assertEquals(strs[1], Text.getName(strs[0], true));
        }

        // Text.getName(String path, char delim)
        l = new ArrayList();
        l.add(new String[] {"/a=b/c", "b/c"});
        l.add(new String[] {"c", "c"});
        l.add(new String[] {null, null});
        l.add(new String[] {"", ""});
        l.add(new String[] {"http:/=jackrabbit.apache.org/jackrabbit", "jackrabbit.apache.org/jackrabbit"});
        l.add(new String[] {"http:=//jackrabbit.apache.org/jackrabbit/", "//jackrabbit.apache.org/jackrabbit/"});

        for (Iterator it = l.iterator(); it.hasNext();) {
            String[] strs = (String[]) it.next();
            assertEquals(strs[1], Text.getName(strs[0], '='));
        }
    }

    /**
     * @see <a href="https://issues.apache.org/jira/browse/JCR-1926">JCR-1926</a>
     */
    public void testUnescapeWithInvalidInput() {
        assertInvalidUnescape("%");   // too short
        assertInvalidUnescape("%%");  // too short
        assertInvalidUnescape("%%%"); // not a number
    }

    public void testGetJSONString() {
        Map m = new HashMap();
        m.put("abc", "\"abc\"");
        m.put("a \"b\" c", "\"a \\\"b\\\" c\"");
        m.put("a\tb\rc\nd\fe\b", "\"a\\tb\\rc\\nd\\fe\\b\"");
        m.put("\\abc", "\"\\\\abc\"");
        m.put("abc", "\"abc\"");

        // non-printable ascii other than those treated (\t,\r,\n)
        m.put(String.valueOf((char) 7), "\"\\u0007\"");
        m.put(String.valueOf((char) 30), "\"\\u001e\"");

        // chinese
        m.put("\u4e00a\u4e8cb\u4e09c", "\"\u4e00a\u4e8cb\u4e09c\"");
        /* arabic */
        m.put("\u062c\u062f\u064a\u062f", "\"\u062c\u062f\u064a\u062f\"");
        /* �a�b?c */
        m.put("\u00d1a\u00e7b\u0416c", "\"\u00d1a\u00e7b\u0416c\"");
        // ����
        // m.put("����", "\"\u00e2\u00e8\u00f8\u00fc\"");

        for (Iterator it = m.keySet().iterator(); it.hasNext();) {
            String key = it.next().toString();
            assertEquals(m.get(key).toString(), Text.getJSONString(key));
        }
    }
    
    private void assertInvalidUnescape(String string) {
        try {
            Text.unescape(string);
        } catch (IllegalArgumentException expected) {
        } catch (RuntimeException unexpected) {
            fail("Text.unescape(" + string + "): " + unexpected.getMessage());
        }
    }

}
