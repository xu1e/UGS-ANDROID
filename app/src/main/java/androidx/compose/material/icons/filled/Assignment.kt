/*
 * Copyright 2025 The Android Open Source Project
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

package androidx.compose.material.icons.filled

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.ui.graphics.vector.ImageVector
import kotlin.Deprecated

@Deprecated(
    "Use the AutoMirrored version at Icons.AutoMirrored.Filled.Assignment",
    ReplaceWith( "Icons.AutoMirrored.Filled.Assignment",
            "androidx.compose.material.icons.automirrored.filled.Assignment"),
)
public val Icons.Filled.Assignment: ImageVector
    get() {
        if (_assignment != null) {
            return _assignment!!
        }
        _assignment = materialIcon(name = "Filled.Assignment") {
            materialPath {
                moveTo(19.0f, 3.0f)
                horizontalLineToRelative(-4.18f)
                curveTo(14.4f, 1.84f, 13.3f, 1.0f, 12.0f, 1.0f)
                curveToRelative(-1.3f, 0.0f, -2.4f, 0.84f, -2.82f, 2.0f)
                lineTo(5.0f, 3.0f)
                curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
                verticalLineToRelative(14.0f)
                curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
                horizontalLineToRelative(14.0f)
                curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                lineTo(21.0f, 5.0f)
                curveToRelative(0.0f, -1.1f, -0.9f, -2.0f, -2.0f, -2.0f)
                close()
                moveTo(12.0f, 3.0f)
                curveToRelative(0.55f, 0.0f, 1.0f, 0.45f, 1.0f, 1.0f)
                reflectiveCurveToRelative(-0.45f, 1.0f, -1.0f, 1.0f)
                reflectiveCurveToRelative(-1.0f, -0.45f, -1.0f, -1.0f)
                reflectiveCurveToRelative(0.45f, -1.0f, 1.0f, -1.0f)
                close()
                moveTo(14.0f, 17.0f)
                lineTo(7.0f, 17.0f)
                verticalLineToRelative(-2.0f)
                horizontalLineToRelative(7.0f)
                verticalLineToRelative(2.0f)
                close()
                moveTo(17.0f, 13.0f)
                lineTo(7.0f, 13.0f)
                verticalLineToRelative(-2.0f)
                horizontalLineToRelative(10.0f)
                verticalLineToRelative(2.0f)
                close()
                moveTo(17.0f, 9.0f)
                lineTo(7.0f, 9.0f)
                lineTo(7.0f, 7.0f)
                horizontalLineToRelative(10.0f)
                verticalLineToRelative(2.0f)
                close()
            }
        }
        return _assignment!!
    }

private var _assignment: ImageVector? = null
