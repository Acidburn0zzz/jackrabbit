<?php

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


require_once 'PHPCR/RepositoryException.php';


/**
 * Exception thrown when an action would violate
 * a constraint on repository structure. For example, when an attempt is made
 * to persistently add an item to a node that would violate that node's node type.
 *
 * @author Markus Nix <mnix@mayflower.de>
 * @package phpcr
 * @subpackage nodetype
 */
class ConstraintViolationException extends RepositoryException
{
}

?>