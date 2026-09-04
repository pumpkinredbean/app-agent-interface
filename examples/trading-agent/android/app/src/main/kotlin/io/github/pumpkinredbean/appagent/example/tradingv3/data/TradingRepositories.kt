package io.github.pumpkinredbean.appagent.example.tradingv3.data

import io.github.pumpkinredbean.appagent.example.tradingv3.model.DepthLevel
import io.github.pumpkinredbean.appagent.example.tradingv3.model.LocalOrder
import io.github.pumpkinredbean.appagent.example.tradingv3.model.MarketAsset
import io.github.pumpkinredbean.appagent.example.tradingv3.model.PortfolioSnapshot
import io.github.pumpkinredbean.appagent.example.tradingv3.model.Position
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

class MarketRepository {
    private val fixtures = linkedMapOf(
        "BTC" to fixture("BTC", "Bitcoin", 80_957.21, 4.28, 82_300.0, 77_478.0, 1_728_355_672.0, 0.82),
        "ETH" to fixture("ETH", "Ethereum", 3_427.44, 2.16, 3_502.2, 3_311.5, 921_833_401.0, 12.4),
        "SOL" to fixture("SOL", "Solana", 166.42, -1.08, 171.8, 162.1, 488_240_004.0, 220.0),
        "BNB" to fixture("BNB", "BNB", 812.31, 1.34, 827.4, 791.2, 304_121_778.0, 38.0),
    )

    fun supported(): List<MarketAsset> = fixtures.values.toList()

    fun supports(symbol: String): Boolean =
        symbol.uppercase().removeSuffix("USDT") in fixtures

    fun cached(symbol: String): MarketAsset {
        val normalized = symbol.uppercase().removeSuffix("USDT")
        return requireNotNull(fixtures[normalized]) {
            "Unsupported market symbol: $normalized"
        }
    }

    suspend fun quote(symbol: String): MarketAsset = withContext(Dispatchers.IO) {
        val normalized = symbol.uppercase().removeSuffix("USDT")
        val fallback = cached(normalized)
        runCatching { fetchBinance(normalized, fallback) }.getOrDefault(fallback)
    }

    private fun fetchBinance(symbol: String, fallback: MarketAsset): MarketAsset {
        val ticker = getJson("https://api.binance.com/api/v3/ticker/24hr?symbol=${symbol}USDT")
        val depth = getJson("https://api.binance.com/api/v3/depth?symbol=${symbol}USDT&limit=5")
        val price = ticker.getString("lastPrice").toDouble()
        val bids = depth.getJSONArray("bids").toLevels()
        val asks = depth.getJSONArray("asks").toLevels()
        val refreshed = fallback.copy(
            price = price,
            changePercent = ticker.getString("priceChangePercent").toDouble(),
            high = ticker.getString("highPrice").toDouble(),
            low = ticker.getString("lowPrice").toDouble(),
            quoteVolume = ticker.getString("quoteVolume").toDouble(),
            series = (fallback.series.drop(1) + price),
            bids = bids,
            asks = asks,
        )
        fixtures[symbol] = refreshed
        return refreshed
    }

    private fun getJson(url: String): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 4_000
            connection.readTimeout = 4_000
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/json")
            if (connection.responseCode !in 200..299) error("HTTP ${connection.responseCode}")
            JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }

    private fun org.json.JSONArray.toLevels(): List<DepthLevel> = buildList {
        repeat(length()) { index ->
            val row = getJSONArray(index)
            add(DepthLevel(row.getString(0).toDouble(), row.getString(1).toDouble()))
        }
    }

    private fun fixture(
        symbol: String,
        name: String,
        price: Double,
        change: Double,
        high: Double,
        low: Double,
        volume: Double,
        depth: Double,
    ): MarketAsset {
        val multipliers = listOf(.982, .991, .987, .996, 1.004, .999, 1.011, 1.006, 1.018, 1.012, 1.026, 1.0)
        return MarketAsset(
            symbol = symbol,
            name = name,
            price = price,
            changePercent = change,
            high = high,
            low = low,
            quoteVolume = volume,
            series = multipliers.map { price * it },
            bids = (1..5).map { DepthLevel(price - it * price * .00004, depth / it) },
            asks = (1..5).map { DepthLevel(price + it * price * .00004, depth / (it + .5)) },
        )
    }
}

class PortfolioRepository {
    private var cash = 100_000.0
    private val positions = linkedMapOf(
        "BTC" to Position("BTC", "Bitcoin", .3010, 72_100.0),
        "ETH" to Position("ETH", "Ethereum", 5.420, 3_107.0),
        "SOL" to Position("SOL", "Solana", 42.0, 153.2),
    )
    private val orders = mutableListOf(
        LocalOrder("LOCAL-2", "BTC", .001, 78.41, 78_410.0),
        LocalOrder("LOCAL-1", "ETH", .030, 98.10, 3_270.0),
    )

    fun snapshot(): PortfolioSnapshot = PortfolioSnapshot(cash, positions.values.toList(), orders.toList())

    fun fill(symbol: String, budget: Double, price: Double): LocalOrder {
        require(budget > 0 && budget <= cash)
        val normalized = symbol.uppercase()
        val quantity = budget / price
        val previous = positions[normalized]
        val next = if (previous == null) {
            Position(normalized, normalized, quantity, price)
        } else {
            val totalCost = previous.quantity * previous.averageCost + budget
            previous.copy(
                quantity = previous.quantity + quantity,
                averageCost = totalCost / (previous.quantity + quantity),
            )
        }
        positions[normalized] = next
        cash -= budget
        return LocalOrder(
            id = "LOCAL-${UUID.randomUUID().toString().take(6).uppercase()}",
            symbol = normalized,
            quantity = quantity,
            budget = budget,
            price = price,
        ).also { orders.add(0, it) }
    }
}
