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
                     java.io.IOException"
%><%@page session="false" %><%
Session repSession = RepositoryServlet.getSession();
if (repSession == null) {
    return;
}

String excludeList = request.getParameter("ExcludeList");
String version = request.getParameter("version");
%>version:<%= version %>%><br><%
%>excludeList:<%= excludeList %>%><br><%
if (excludeList != null && version != null) {
    Node excludeListNode = (repSession.getRootNode().hasNode("excludeList")) ?
            repSession.getRootNode().getNode("excludeList") :
            repSession.getRootNode().addNode("excludeList", "nt:unstructured");

    excludeListNode.setProperty("version", version);
    excludeListNode.setProperty("list", excludeList);
    repSession.save();
}
%>