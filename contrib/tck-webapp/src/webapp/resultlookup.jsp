<%--
Copyright 2004-2005 The Apache Software Foundation or its licensors,
                    as applicable.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

     http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
--%><%@ page import="javax.jcr.Session,
                     org.apache.jackrabbit.tck.j2ee.RepositoryServlet,
                     javax.jcr.Node,
                     javax.jcr.RepositoryException,
                     java.util.HashMap,
                     java.io.IOException,
                     java.io.ByteArrayOutputStream,
                     org.apache.jackrabbit.tck.TestResultParser,
                     java.util.Map"
%><%@page session="false" %><%
Session repSession = RepositoryServlet.getSession();
if (repSession == null) {
    return;
}

String sampleDate = request.getParameter("sampledate");
if (sampleDate != null && repSession.getRootNode().hasNode("testing/" + sampleDate)) {
    // build xml
    Node testroot = repSession.getRootNode().getNode("testing/" + sampleDate);
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    repSession.exportSysView(testroot.getPath(), baos, false, false);
    %><%= baos.toString() %><%
    }
%>