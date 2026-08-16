/*
 * Copyright 2026 Glavo
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.glavo.nbt.validation;

/// Exception thrown when an NBT element is not valid.
public class NBTValidationException extends Exception {
    public NBTValidationException() {
    }

    public NBTValidationException(String message) {
        super(message);
    }

    public NBTValidationException(String message, Throwable cause) {
        super(message, cause);
    }

    public NBTValidationException(Throwable cause) {
        super(cause);
    }

    public NBTValidationException(String message, Throwable cause, boolean enableSuppression, boolean writableStackTrace) {
        super(message, cause, enableSuppression, writableStackTrace);
    }
}
