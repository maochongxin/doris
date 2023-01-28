// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

#pragma once

#include "olap/row_cursor.h"
#include "olap/schema.h"
#include "runtime/descriptors.h"
#include "runtime/raw_value.h"
#include "runtime/tuple.h"

namespace doris {
class RowComparator {
public:
    RowComparator() = default;
    RowComparator(Schema* schema);
    virtual ~RowComparator() = default;
    virtual int operator()(const char* left, const char* right) const;
};
} // namespace doris
