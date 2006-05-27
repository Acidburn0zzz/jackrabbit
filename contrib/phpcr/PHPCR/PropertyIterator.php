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


require_once 'PHPCR/RangeIterator.php';
require_once 'PHPCR/NoSuchElementException.php';
require_once 'PHPCR/Property.php';


/**
 * Allows easy iteration through a list of <code>Property</code>s
 * with <code>nextProperty</code> as well as a <code>skip</code> method.
 *
 * @author Markus Nix <mnix@mayflower.de>
 * @package phpcr
 */
interface PropertyIterator extends RangeIterator
{
    /**
     * Returns the next <code>Property</code> in the iteration.
     *
     * @return the next <code>Property</code> in the iteration.
     * @throws NoSuchElementException if iteration has no more <code>Property</code>s.
     */
    public function nextProperty();
}

?>