package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
fun HudColorPickerDialog(
    target: HudColorTarget,
    initialColorArgb: Int,
    onDismiss: () -> Unit,
    onColorConfirmed: (Int) -> Unit
) {
    val sanitizedInitial = remember(target, initialColorArgb) {
        HudColorSystem.sanitizeOpaqueColor(initialColorArgb, HudColorSystem.DEFAULT_ACTIVE_LYRIC_COLOR)
    }
    val initialHsv = remember(sanitizedInitial) {
        HudColorSystem.rgbToHsv(sanitizedInitial)
    }

    var hue by remember(sanitizedInitial) { mutableFloatStateOf(initialHsv[0]) }
    var saturation by remember(sanitizedInitial) { mutableFloatStateOf(initialHsv[1]) }
    var value by remember(sanitizedInitial) { mutableFloatStateOf(initialHsv[2]) }
    var selectedArgb by remember(sanitizedInitial) { mutableIntStateOf(sanitizedInitial) }
    var hexInput by remember(sanitizedInitial) {
        mutableStateOf(HudColorSystem.formatHexColor(sanitizedInitial))
    }
    var hexError by remember(sanitizedInitial) { mutableStateOf<String?>(null) }

    val focusManager = LocalFocusManager.current

    fun updateFromHsv(newH: Float, newS: Float, newV: Float) {
        hue = newH.coerceIn(0f, 360f)
        saturation = newS.coerceIn(0f, 1f)
        value = newV.coerceIn(0f, 1f)
        val argb = HudColorSystem.hsvToArgb(hue, saturation, value)
        selectedArgb = argb
        hexInput = HudColorSystem.formatHexColor(argb)
        hexError = null
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp)
                .widthIn(max = 420.dp)
                .testTag("hud_color_picker_dialog"),
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            border = BorderStroke(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = target.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = target.subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(Color(selectedArgb))
                            .border(
                                width = 2.dp,
                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
                                shape = CircleShape
                            )
                            .semantics {
                                contentDescription = "Selected colour ${HudColorSystem.formatHexColor(selectedArgb)}"
                            }
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Mini readability previews over Black and Grey backdrops
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    BackdropContrastChip(
                        label = "On Black",
                        backdropArgb = HudColorSystem.BLACK_BACKDROP_COLOR,
                        candidateArgb = selectedArgb,
                        modifier = Modifier.weight(1f)
                    )
                    BackdropContrastChip(
                        label = "On Grey",
                        backdropArgb = HudColorSystem.GREY_BACKDROP_COLOR,
                        candidateArgb = selectedArgb,
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // 2D Saturation & Value Pad
                SaturationValuePad(
                    hue = hue,
                    saturation = saturation,
                    value = value,
                    onSaturationValueChanged = { s, v ->
                        focusManager.clearFocus(force = false)
                        updateFromHsv(hue, s, v)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(148.dp)
                        .testTag("hud_color_sv_pad")
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Hue Spectrum Bar
                HueSpectrumBar(
                    hue = hue,
                    onHueChanged = { newHue ->
                        focusManager.clearFocus(force = false)
                        updateFromHsv(newHue, saturation, value)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(34.dp)
                        .testTag("hud_color_hue_bar")
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Hex input with live validation
                OutlinedTextField(
                    value = hexInput,
                    onValueChange = { raw ->
                        val trimmed = raw.take(9)
                        hexInput = trimmed
                        val parsed = HudColorSystem.parseHexColorOrNull(trimmed)
                        if (parsed != null) {
                            selectedArgb = parsed
                            val hsv = HudColorSystem.rgbToHsv(parsed)
                            hue = hsv[0]
                            saturation = hsv[1]
                            value = hsv[2]
                            hexError = null
                        } else {
                            hexError = "Enter a valid #RRGGBB hex colour"
                        }
                    },
                    label = { Text("Hex Colour") },
                    placeholder = { Text("#RRGGBB") },
                    singleLine = true,
                    isError = hexError != null,
                    supportingText = hexError?.let { err ->
                        { Text(text = err) }
                    },
                    textStyle = LocalTextStyle.current.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold
                    ),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = { focusManager.clearFocus() }
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("hud_color_hex_input")
                )

                Spacer(modifier = Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("hud_color_picker_cancel"),
                        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp)
                    ) {
                        Text("Cancel")
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Button(
                        onClick = {
                            val confirmed = HudColorSystem.parseHexColorOrNull(hexInput) ?: selectedArgb
                            onColorConfirmed(
                                HudColorSystem.sanitizeOpaqueColor(
                                    confirmed,
                                    HudColorSystem.DEFAULT_ACTIVE_LYRIC_COLOR
                                )
                            )
                        },
                        enabled = hexError == null,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("hud_color_picker_apply"),
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp)
                    ) {
                        Text("Apply Colour")
                    }
                }
            }
        }
    }
}

@Composable
private fun BackdropContrastChip(
    label: String,
    backdropArgb: Int,
    candidateArgb: Int,
    modifier: Modifier = Modifier
) {
    val ratio = remember(backdropArgb, candidateArgb) {
        HudColorSystem.contrastRatio(candidateArgb, backdropArgb)
    }
    val textColorOnBackdrop = if (HudColorSystem.isDarkColor(backdropArgb)) Color.White else Color(0xFF141418)

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = Color(backdropArgb),
        border = BorderStroke(
            width = 1.dp,
            color = Color.Gray.copy(alpha = 0.35f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = label,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Color(candidateArgb),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = String.format(java.util.Locale.US, "%.1f:1", ratio),
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                color = textColorOnBackdrop.copy(alpha = 0.72f)
            )
        }
    }
}

@Composable
private fun SaturationValuePad(
    hue: Float,
    saturation: Float,
    value: Float,
    onSaturationValueChanged: (Float, Float) -> Unit,
    modifier: Modifier = Modifier
) {
    var padSize by remember { mutableStateOf(IntSize.Zero) }
    val pureHueColor = remember(hue) {
        Color(HudColorSystem.hsvToArgb(hue, 1f, 1f))
    }

    fun emitAt(offset: Offset) {
        if (padSize.width <= 0 || padSize.height <= 0) return
        val s = (offset.x / padSize.width.toFloat()).coerceIn(0f, 1f)
        val v = (1f - (offset.y / padSize.height.toFloat())).coerceIn(0f, 1f)
        onSaturationValueChanged(s, v)
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
                shape = RoundedCornerShape(14.dp)
            )
            .onSizeChanged { padSize = it }
            .pointerInput(Unit) {
                detectTapGestures { offset -> emitAt(offset) }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    change.consume()
                    emitAt(change.position)
                }
            }
            .semantics {
                contentDescription = "Colour saturation and brightness pad"
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRoundRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(Color.White, pureHueColor)
                ),
                cornerRadius = CornerRadius(14.dp.toPx(), 14.dp.toPx())
            )
            drawRoundRect(
                brush = Brush.verticalGradient(
                    colors = listOf(Color.Transparent, Color.Black)
                ),
                cornerRadius = CornerRadius(14.dp.toPx(), 14.dp.toPx())
            )

            val cx = (saturation.coerceIn(0f, 1f) * size.width)
            val cy = ((1f - value.coerceIn(0f, 1f)) * size.height)
            val radius = 9.dp.toPx()
            drawCircle(
                color = Color.Black.copy(alpha = 0.55f),
                radius = radius + 1.5.dp.toPx(),
                center = Offset(cx, cy),
                style = Stroke(width = 2.dp.toPx())
            )
            drawCircle(
                color = Color.White,
                radius = radius,
                center = Offset(cx, cy),
                style = Stroke(width = 2.5.dp.toPx())
            )
        }
    }
}

@Composable
private fun HueSpectrumBar(
    hue: Float,
    onHueChanged: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    var barSize by remember { mutableStateOf(IntSize.Zero) }
    val spectrumColors = remember {
        listOf(
            Color(0xFFFF0000),
            Color(0xFFFFFF00),
            Color(0xFF00FF00),
            Color(0xFF00FFFF),
            Color(0xFF0000FF),
            Color(0xFFFF00FF),
            Color(0xFFFF0000)
        )
    }

    fun emitHue(offset: Offset) {
        if (barSize.width <= 0) return
        val newHue = ((offset.x / barSize.width.toFloat()).coerceIn(0f, 1f) * 360f)
        onHueChanged(newHue)
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(17.dp))
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
                shape = RoundedCornerShape(17.dp)
            )
            .onSizeChanged { barSize = it }
            .pointerInput(Unit) {
                detectTapGestures { offset -> emitHue(offset) }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    change.consume()
                    emitHue(change.position)
                }
            }
            .semantics {
                contentDescription = "Hue slider"
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val corner = CornerRadius(17.dp.toPx(), 17.dp.toPx())
            drawRoundRect(
                brush = Brush.horizontalGradient(colors = spectrumColors),
                cornerRadius = corner
            )

            val cx = ((hue.coerceIn(0f, 360f) / 360f) * size.width)
                .coerceIn(10.dp.toPx(), (size.width - 10.dp.toPx()).coerceAtLeast(10.dp.toPx()))
            val cy = size.height / 2f
            drawCircle(
                color = Color.Black.copy(alpha = 0.45f),
                radius = 10.dp.toPx(),
                center = Offset(cx, cy),
                style = Stroke(width = 2.dp.toPx())
            )
            drawCircle(
                color = Color.White,
                radius = 8.5.dp.toPx(),
                center = Offset(cx, cy),
                style = Stroke(width = 2.5.dp.toPx())
            )
        }
    }
}
