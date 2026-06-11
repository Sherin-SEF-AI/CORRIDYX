package ai.deepmost.corridyx.ui.screens

import ai.deepmost.corridyx.ui.theme.Accent
import ai.deepmost.corridyx.ui.theme.Hairline
import ai.deepmost.corridyx.ui.theme.TextDim
import ai.deepmost.corridyx.ui.theme.TextHi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@Composable
fun LabeledField(label: String, value: String, placeholder: String, onCommit: (String) -> Unit) {
    var text by remember { mutableStateOf(value) }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, color = TextDim, style = MaterialTheme.typography.labelSmall)
        OutlinedTextField(
            value = text,
            onValueChange = { text = it; onCommit(it) },
            placeholder = { Text(placeholder, color = TextDim, style = MaterialTheme.typography.bodySmall) },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = TextHi, unfocusedTextColor = TextHi,
                focusedBorderColor = Accent, unfocusedBorderColor = Hairline,
                cursorColor = Accent,
            ),
        )
    }
}

@Composable
fun LabeledSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = TextHi, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun LabeledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, steps: Int = 0, format: (Float) -> String, onChange: (Float) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(label, color = TextDim, modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
            Text(format(value), color = TextHi, style = MaterialTheme.typography.labelSmall)
        }
        Slider(
            value = value, onValueChange = onChange, valueRange = range, steps = steps,
            colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent, inactiveTrackColor = Hairline),
        )
    }
}
