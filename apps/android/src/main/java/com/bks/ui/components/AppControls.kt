package com.bks.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button as MaterialButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox as MaterialCheckbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.FloatingActionButton as MaterialFloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.OutlinedButton as MaterialOutlinedButton
import androidx.compose.material3.OutlinedTextField as MaterialOutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton as MaterialTextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

private val ControlInk = Color(0xFF202A44)
private val ControlPrimary = Color(0xFF5654DF)
private val ControlDanger = Color(0xFFFF6F61)
private val ControlSurface = Color(0xFFFFFEFA)
private val ControlShape = RoundedCornerShape(8.dp)

@Composable
fun Button(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit
) {
    MaterialButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = ControlShape,
        border = BorderStroke(1.5.dp, if (enabled) ControlInk else ControlInk.copy(alpha = 0.22f)),
        colors = ButtonDefaults.buttonColors(
            containerColor = ControlPrimary,
            contentColor = Color.White,
            disabledContainerColor = Color(0xFFE0DED9),
            disabledContentColor = ControlInk.copy(alpha = 0.38f)
        ),
        elevation = ButtonDefaults.buttonElevation(
            defaultElevation = 2.dp,
            pressedElevation = 0.dp,
            disabledElevation = 0.dp
        ),
        content = content
    )
}

@Composable
fun DangerButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit
) {
    MaterialButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = ControlShape,
        border = BorderStroke(1.5.dp, if (enabled) ControlInk else ControlInk.copy(alpha = 0.22f)),
        colors = ButtonDefaults.buttonColors(
            containerColor = ControlDanger,
            contentColor = Color.White,
            disabledContainerColor = Color(0xFFE0DED9),
            disabledContentColor = ControlInk.copy(alpha = 0.38f)
        ),
        elevation = ButtonDefaults.buttonElevation(
            defaultElevation = 2.dp,
            pressedElevation = 0.dp,
            disabledElevation = 0.dp
        ),
        content = content
    )
}

@Composable
fun OutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit
) {
    MaterialOutlinedButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = ControlShape,
        border = BorderStroke(1.5.dp, if (enabled) ControlInk else ControlInk.copy(alpha = 0.20f)),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = ControlSurface,
            contentColor = ControlInk,
            disabledContainerColor = ControlSurface.copy(alpha = 0.58f),
            disabledContentColor = ControlInk.copy(alpha = 0.28f)
        ),
        content = content
    )
}

@Composable
fun TextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit
) {
    MaterialTextButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = ControlShape,
        colors = ButtonDefaults.textButtonColors(
            contentColor = ControlPrimary,
            disabledContentColor = ControlInk.copy(alpha = 0.28f)
        ),
        content = content
    )
}

@Composable
fun OutlinedTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    label: (@Composable () -> Unit)? = null,
    placeholder: (@Composable () -> Unit)? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    supportingText: (@Composable () -> Unit)? = null,
    isError: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
    interactionSource: MutableInteractionSource? = null
) {
    MaterialOutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        readOnly = readOnly,
        label = label,
        placeholder = placeholder,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        supportingText = supportingText,
        isError = isError,
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        singleLine = singleLine,
        maxLines = maxLines,
        minLines = minLines,
        interactionSource = interactionSource,
        shape = ControlShape,
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = ControlInk,
            unfocusedTextColor = ControlInk,
            focusedContainerColor = ControlSurface,
            unfocusedContainerColor = ControlSurface,
            disabledContainerColor = ControlSurface.copy(alpha = 0.55f),
            errorContainerColor = ControlSurface,
            cursorColor = ControlPrimary,
            errorCursorColor = ControlDanger,
            focusedBorderColor = ControlPrimary,
            unfocusedBorderColor = ControlInk,
            disabledBorderColor = ControlInk.copy(alpha = 0.18f),
            errorBorderColor = ControlDanger,
            focusedLabelColor = ControlPrimary,
            unfocusedLabelColor = ControlInk.copy(alpha = 0.70f),
            errorLabelColor = ControlDanger,
            focusedLeadingIconColor = ControlPrimary,
            unfocusedLeadingIconColor = ControlInk.copy(alpha = 0.72f),
            errorLeadingIconColor = ControlDanger,
            focusedTrailingIconColor = ControlPrimary,
            unfocusedTrailingIconColor = ControlInk.copy(alpha = 0.72f),
            errorTrailingIconColor = ControlDanger
        )
    )
}

@Composable
fun EmptyStatePanel(
    text: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = ControlShape,
        color = ControlSurface.copy(alpha = 0.92f),
        border = BorderStroke(1.5.dp, ControlInk)
    ) {
        Text(
            text = text,
            color = ControlInk.copy(alpha = 0.78f),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 18.dp)
        )
    }
}

@Composable
fun FloatingActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    MaterialFloatingActionButton(
        onClick = onClick,
        modifier = modifier.border(1.5.dp, ControlInk, CircleShape),
        shape = CircleShape,
        containerColor = ControlPrimary,
        contentColor = Color.White,
        elevation = FloatingActionButtonDefaults.elevation(
            defaultElevation = 4.dp,
            pressedElevation = 1.dp
        ),
        content = content
    )
}

@Composable
fun Checkbox(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    MaterialCheckbox(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        enabled = enabled,
        colors = CheckboxDefaults.colors(
            checkedColor = ControlPrimary,
            uncheckedColor = ControlInk,
            checkmarkColor = Color.White,
            disabledCheckedColor = ControlInk.copy(alpha = 0.20f),
            disabledUncheckedColor = ControlInk.copy(alpha = 0.16f)
        )
    )
}
