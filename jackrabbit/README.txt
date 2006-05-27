=============================================================
Welcome to Apache Jackrabbit  <http://jackrabbit.apache.org/>
=============================================================

License (see also LICENSE.txt)
==============================

Collective work: Copyright 2006 The Apache Software Foundation.

Licensed to the Apache Software Foundation (ASF) under one or more
contributor license agreements.  See the NOTICE file distributed with
this work for additional information regarding copyright ownership.
The ASF licenses this file to You under the Apache License, Version 2.0
(the "License"); you may not use this file except in compliance with
the License.  You may obtain a copy of the License at

     http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.


Getting Started
===============

Mailing Lists
-------------

To get involved with the Apache Jackrabbit project, start by having a
look at our website (link at top of page) and join our mailing
lists by sending an empty message to

   dev-subscribe     :at: jackrabbit.apache.org
and
   commits-subscribe :at: jackrabbit.apache.org

and the dev mailing list archives can be found at

   http://jackrabbit.apache.org/mail/dev/


Downloading
-----------

The Jackrabbit source code is available via Subversion at

   https://svn.apache.org/repos/asf/jackrabbit/trunk

and anonymous access is available at

   http://svn.apache.org/repos/asf/jackrabbit/trunk

or with ViewVC at

   http://svn.apache.org/viewvc/jackrabbit/trunk/

The Jackrabbit main project is located in the "jackrabbit" subdirectory
and the "contrib" subdirectory contains various additional modules and
contributed projects.

To checkout the main Jackrabbit source tree, run

   svn checkout http://svn.apache.org/repos/asf/jackrabbit/trunk/jackrabbit

Once you have a copy of the source code tree, you can use Apache Maven

   http://maven.apache.org/maven-1.x/

to build the project. You should use Maven version 1.0.2 to build Jackrabbit.
Maven 1.1 is also known to work, but Maven 2.0 is not supported. The minimal
command to build and test all the Jackrabbit sources is:

   maven

For more instructions, please see the documentation at:

   http://jackrabbit.apache.org/doc/building.html

Credits
=======

who                     what
--------------------    -----------------------------------------------
Roy Fielding            incubation
Stefan Guggisberg       core, data model, persistence, nodetypes, misc.
David Nuescheler        architecture, api
Dominique Pfister       transactions
Peeter Piegaze          api
Tim Reilly              mavenize
Marcel Reutegger        observation, query
Tobias Bocanegra        versioning


Changes
=======

See <http://jackrabbit.apache.org/changelog-report.html>
