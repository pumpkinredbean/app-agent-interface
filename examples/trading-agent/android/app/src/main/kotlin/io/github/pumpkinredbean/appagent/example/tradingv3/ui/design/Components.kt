package io.github.pumpkinredbean.appagent.example.tradingv3.ui.design

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs

@Composable
fun MarketText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = LocalMarketColors.current.text,
    size: Int = 15,
    weight: FontWeight = FontWeight.Normal,
    family: FontFamily = FontFamily.SansSerif,
    align: TextAlign? = null,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = TextStyle(
            color = color,
            fontSize = size.sp,
            lineHeight = (size * 1.34f).sp,
            fontWeight = weight,
            fontFamily = family,
            textAlign = align ?: TextAlign.Unspecified,
        ),
        maxLines = maxLines,
        overflow = overflow,
    )
}

@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier) {
    MarketText(
        text.uppercase(),
        modifier,
        color = LocalMarketColors.current.accent,
        size = 12,
        weight = FontWeight.Bold,
        family = FontFamily.Monospace,
    )
}

@Composable
fun Panel(
    modifier: Modifier = Modifier,
    radius: Dp = 18.dp,
    padding: PaddingValues = PaddingValues(18.dp),
    content: @Composable () -> Unit,
) {
    val colors = LocalMarketColors.current
    Box(
        modifier
            .clip(RoundedCornerShape(radius))
            .background(colors.raised)
            .border(BorderStroke(1.dp, colors.line), RoundedCornerShape(radius))
            .padding(padding),
    ) { content() }
}

@Composable
fun ActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = true,
    enabled: Boolean = true,
) {
    val colors = LocalMarketColors.current
    val shape = RoundedCornerShape(14.dp)
    val background = when {
        !enabled -> colors.soft
        primary -> colors.text
        else -> colors.raised
    }
    val foreground = when {
        !enabled -> colors.textDim
        primary -> colors.page
        else -> colors.text
    }
    Box(
        modifier
            .defaultMinSize(minHeight = 48.dp)
            .clip(shape)
            .background(background)
            .border(1.dp, if (primary) colors.text else colors.lineStrong, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        MarketText(text, color = foreground, size = 14, weight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
fun IconButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalMarketColors.current
    Box(
        modifier
            .size(44.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(colors.raised)
            .border(1.dp, colors.line, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        MarketText(label, size = 18, weight = FontWeight.Medium)
    }
}

@Composable
fun StatusPill(
    text: String,
    modifier: Modifier = Modifier,
    active: Boolean = true,
) {
    val colors = LocalMarketColors.current
    Row(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (active) colors.accentSoft else colors.soft)
            .border(1.dp, if (active) colors.accent.copy(alpha = .28f) else colors.line, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(if (active) colors.accent else colors.textDim))
        MarketText(text, color = if (active) colors.accent else colors.textDim, size = 11, weight = FontWeight.Bold)
    }
}

@Composable
fun Metric(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = LocalMarketColors.current.text,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        MarketText(label, color = LocalMarketColors.current.textDim, size = 11)
        MarketText(value, color = valueColor, size = 15, weight = FontWeight.SemiBold, family = FontFamily.Monospace)
    }
}

@Composable
fun Divider(modifier: Modifier = Modifier) {
    Spacer(modifier.fillMaxWidth().height(1.dp).background(LocalMarketColors.current.line))
}

fun money(value: Double, digits: Int = 2): String {
    val format = NumberFormat.getNumberInstance(Locale.US).apply {
        minimumFractionDigits = digits
        maximumFractionDigits = digits
    }
    return format.format(value)
}

fun compactMoney(value: Double): String = when {
    abs(value) >= 1_000_000_000 -> "${money(value / 1_000_000_000, 2)}B"
    abs(value) >= 1_000_000 -> "${money(value / 1_000_000, 2)}M"
    else -> money(value)
}

fun signedMoney(value: Double): String = "${if (value >= 0) "+" else "−"}${money(abs(value))}"
fun signedPercent(value: Double): String = String.format(Locale.US, "%+.2f%%", value)

fun Modifier.quietClickable(onClick: () -> Unit): Modifier = clickable(
    interactionSource = MutableInteractionSource(),
    indication = null,
    onClick = onClick,
)
