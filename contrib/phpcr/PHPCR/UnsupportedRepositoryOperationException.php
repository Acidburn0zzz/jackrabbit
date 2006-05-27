<?php

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


require_once 'PHPCR/RepositoryException.php';


/**
 * In level 1 implementations, exception thrown by methods that are only
 * relevant to level 2 implementations, in level 2 may be thrown by methods
 * related to optional features (such as locking) which are missing from a
 * particular level 2 implementation.
 *
 * @author Markus Nix <mnix@mayflower.de>
 * @package phpcr
 */
class UnsupportedRepositoryOperationException extends RepositoryException
{
}

?>