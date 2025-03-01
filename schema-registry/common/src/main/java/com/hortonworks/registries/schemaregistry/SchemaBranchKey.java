/**
 * Copyright 2017 Hortonworks.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 **/

package com.hortonworks.registries.schemaregistry;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.io.Serializable;
import java.util.Objects;

/**
 * This class contains schema branch name and schema metadata name.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class SchemaBranchKey implements Serializable {

    private static final long serialVersionUID = -4527140700667642331L;

    private String schemaBranchName;
    private String schemaMetadataName;

    /**
     * Private constructor for Jackson JSON mapping
     */
    @SuppressWarnings("unused")
    private SchemaBranchKey() {
    }

    /**
     * @param schemaBranchName      schema branch name
     * @param schemaMetadataName    schema metadata name from which the schema branch was created
     */
    public SchemaBranchKey(String schemaBranchName, String schemaMetadataName) {
        Objects.requireNonNull(schemaBranchName, "schemaBranchName can not be null");
        Objects.requireNonNull(schemaMetadataName, "schemaMetadataName can not be null");
        this.schemaBranchName = schemaBranchName;
        this.schemaMetadataName = schemaMetadataName;
    }


    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        SchemaBranchKey that = (SchemaBranchKey) o;

        if (schemaBranchName != null ? !schemaBranchName.equals(that.schemaBranchName) : that.schemaBranchName != null) return false;
        return schemaMetadataName != null ? schemaMetadataName.equals(that.schemaMetadataName) : that.schemaMetadataName == null;

    }

    public String getSchemaBranchName() {
        return schemaBranchName;
    }

    public String getSchemaMetadataName() {
        return schemaMetadataName;
    }

    @Override
    public int hashCode() {
        int result = schemaBranchName != null ? schemaBranchName.hashCode() : 0;
        result = 31 * result + (schemaMetadataName != null ? schemaMetadataName.hashCode() : 0);
        return result;
    }

    @Override
    public String toString() {
        return "SchemaBranchKey {" +
                "schemaBranchName='" + schemaBranchName + '\'' +
                ", schemaMetadataName=" + schemaMetadataName +
                '}';
    }
}
