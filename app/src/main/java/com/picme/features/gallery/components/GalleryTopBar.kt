package com.picme.features.gallery.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.outlined.FilterDrama
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.picme.R
import com.picme.domain.model.GroupingMode
import com.picme.domain.model.GroupingMode.DATE
import com.picme.domain.model.GroupingMode.FACE
import com.picme.domain.model.GroupingMode.LANDSCAPE
import com.picme.domain.model.GroupingMode.NONE
import com.picme.domain.model.GroupingMode.PERSON
import com.picme.domain.model.GroupingMode.SEXY
import com.picme.domain.model.GroupingMode.SWIMWEAR

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryTopBar(
    isSelectionMode: Boolean,
    selectedCount: Int,
    groupingMode: GroupingMode,
    onNavigateBack: () -> Unit,
    onToggleSelectionMode: () -> Unit,
    onSelectAll: () -> Unit,
    onDeleteSelected: () -> Unit,
    onShareSelected: () -> Unit,
    onGroupingModeSelected: (GroupingMode) -> Unit,
    onManageDuplicates: () -> Unit,
    onOpenTestDataTools: () -> Unit
) {
    TopAppBar(
        title = {
            Text(
                if (isSelectionMode) {
                    stringResource(R.string.selected_items, selectedCount)
                } else {
                    stringResource(R.string.gallery)
                }
            )
        },
        navigationIcon = {
            IconButton(onClick = {
                if (isSelectionMode) {
                    onToggleSelectionMode()
                } else {
                    onNavigateBack()
                }
            }) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null)
            }
        },
        actions = {
            if (isSelectionMode) {
                IconButton(onClick = onSelectAll) {
                    Icon(Icons.Rounded.SelectAll, contentDescription = stringResource(R.string.select_all))
                }
                IconButton(onClick = onShareSelected) {
                    Icon(Icons.Rounded.Share, contentDescription = stringResource(R.string.ocr_share))
                }
                IconButton(onClick = onDeleteSelected) {
                    Icon(Icons.Rounded.Delete, contentDescription = stringResource(R.string.delete))
                }
            } else {
                IconButton(onClick = onOpenTestDataTools) {
                    Icon(
                        Icons.Rounded.CloudDownload,
                        contentDescription = stringResource(R.string.test_data_tools)
                    )
                }
                IconButton(onClick = onManageDuplicates) {
                    Icon(Icons.Outlined.FilterDrama, contentDescription = stringResource(R.string.manage_duplicates))
                }
                GroupingMenu(
                    currentMode = groupingMode,
                    onModeSelected = onGroupingModeSelected
                )
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DuplicateManagerTopBar(
    onNavigateBack: () -> Unit,
    onDeleteAllDuplicates: () -> Unit
) {
    TopAppBar(
        title = { Text(stringResource(R.string.manage_duplicates)) },
        navigationIcon = {
            IconButton(onClick = onNavigateBack) {
                Icon(Icons.Rounded.Close, contentDescription = null)
            }
        },
        actions = {
            IconButton(onClick = onDeleteAllDuplicates) {
                Icon(Icons.Rounded.Delete, contentDescription = "Delete All Duplicates")
            }
        }
    )
}

@Composable
private fun GroupingMenu(
    currentMode: GroupingMode,
    onModeSelected: (GroupingMode) -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    androidx.compose.foundation.layout.Box {
        IconButton(onClick = { showMenu = true }) {
            Icon(Icons.AutoMirrored.Rounded.Sort, contentDescription = null)
        }
        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
            GroupingMode.entries.forEach { mode ->
                val label = when (mode) {
                    NONE -> stringResource(R.string.group_none)
                    DATE -> stringResource(R.string.group_date)
                    FACE -> stringResource(R.string.group_face)
                    PERSON -> stringResource(R.string.group_person)
                    LANDSCAPE -> stringResource(R.string.landscape)
                    SWIMWEAR -> stringResource(R.string.swimwear)
                    SEXY -> stringResource(R.string.sexy)
                }
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        onModeSelected(mode)
                        showMenu = false
                    },
                    leadingIcon = {
                        if (currentMode == mode) {
                            Icon(Icons.Rounded.Check, null)
                        }
                    }
                )
            }
        }
    }
}
