package com.picme.features.debug

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Female
import androidx.compose.material.icons.filled.Landscape
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Pool
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.picme.PicMeApplication
import com.picme.R
import com.picme.core.designsystem.PicMeTheme
import com.picme.features.gallery.MediaViewModel
import com.picme.features.gallery.MediaViewModelFactory
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as PicMeApplication
    val scope = app.applicationScope
    val mediaViewModel: MediaViewModel = viewModel(
        factory = MediaViewModelFactory(context, app.repository)
    )

    val isGenerating by SampleDataGenerator.isGenerating.collectAsState()
    val isPaused by SampleDataGenerator.isPaused.collectAsState()
    val progress by SampleDataGenerator.progress.collectAsState()
    val allMedia by mediaViewModel.allMedia.collectAsState()

    DebugContent(
        isGenerating = isGenerating,
        isPaused = isPaused,
        progress = progress,
        onNavigateBack = onNavigateBack,
        onPauseResume = { if (isPaused) SampleDataGenerator.resume() else SampleDataGenerator.pause(context) },
        onStop = { SampleDataGenerator.stop(context) },
        onPopulatePerson = {
            scope.launch {
                SampleDataGenerator.populatePersonTestData(context, app.repository)
            }
        },
        onPopulateLandscape = {
            scope.launch {
                SampleDataGenerator.populateLandscapeTestData(context, app.repository)
            }
        },
        onPopulateSwimwear = {
            scope.launch {
                SampleDataGenerator.populateSwimwearTestData(context, app.repository)
            }
        },
        onPopulateSexy = {
            scope.launch {
                SampleDataGenerator.populateSexyTestData(context, app.repository)
            }
        },
        onClearData = {
            scope.launch {
                SampleDataGenerator.clearTestData(context, app.repository, allMedia)
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DebugContent(
    isGenerating: Boolean,
    isPaused: Boolean,
    progress: String,
    onNavigateBack: () -> Unit,
    onPauseResume: () -> Unit,
    onStop: () -> Unit,
    onPopulatePerson: () -> Unit,
    onPopulateLandscape: () -> Unit,
    onPopulateSwimwear: () -> Unit,
    onPopulateSexy: () -> Unit,
    onClearData: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.debug_tools)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (isGenerating) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(16.dp))
                            Text(progress, style = MaterialTheme.typography.bodyMedium)
                        }
                        Spacer(Modifier.height(16.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = onPauseResume,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text(if (isPaused) stringResource(R.string.resume) else stringResource(R.string.pause))
                            }
                            Button(
                                onClick = onStop,
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer)
                            ) {
                                Icon(Icons.Default.Stop, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.stop))
                            }
                        }
                    }
                }
            }

            Text(
                text = stringResource(R.string.data_generation),
                style = MaterialTheme.typography.titleMedium
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onPopulatePerson,
                        enabled = !isGenerating,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Person, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.person))
                    }

                    Button(
                        onClick = onPopulateLandscape,
                        enabled = !isGenerating,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Landscape, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.landscape))
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onPopulateSwimwear,
                        enabled = !isGenerating,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Pool, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.swimwear))
                    }

                    Button(
                        onClick = onPopulateSexy,
                        enabled = !isGenerating,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Female, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.sexy))
                    }
                }
            }

            Button(
                onClick = onClearData,
                enabled = !isGenerating,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                )
            ) {
                Icon(Icons.Default.DeleteSweep, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.clear_test_data))
            }
        }
    }
}

@Preview(showBackground = true, name = "Generating State")
@Composable
fun DebugScreenGeneratingPreview() {
    PicMeTheme {
        DebugContent(
            isGenerating = true,
            isPaused = false,
            progress = "Generating 10/100...",
            onNavigateBack = {},
            onPauseResume = {},
            onStop = {},
            onPopulatePerson = {},
            onPopulateLandscape = {},
            onPopulateSwimwear = {},
            onPopulateSexy = {},
            onClearData = {}
        )
    }
}
