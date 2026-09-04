package io.github.pumpkinredbean.appagent.ui

/**
 * Platform-neutral tag-to-renderer lookup.
 *
 * Renderer is deliberately generic: an Android host may register Compose
 * renderer objects while a JVM or test host may register any other renderer
 * type without adding a UI framework dependency to this module.
 */
public class UiFrameRendererRegistry<Renderer : Any> {
    private val renderers: MutableMap<String, Renderer> = linkedMapOf()

    public val tags: Set<String>
        get() = renderers.keys.toSet()

    public fun register(tag: String, renderer: Renderer): UiFrameRendererRegistration {
        require(tag.isNotBlank()) { "Renderer tag must not be blank" }
        check(tag !in renderers) { "A renderer is already registered for tag: $tag" }
        renderers[tag] = renderer
        return UiFrameRendererRegistration {
            if (renderers[tag] === renderer) renderers.remove(tag)
        }
    }

    public fun find(frame: UiFrame): Renderer? = renderers[frame.tag]

    public fun require(frame: UiFrame): Renderer = find(frame)
        ?: throw UiFrameRendererNotFoundException(frame.tag)
}

public class UiFrameRendererRegistration internal constructor(
    private val unregister: () -> Unit,
) : AutoCloseable {
    private var closed: Boolean = false

    override fun close() {
        if (closed) return
        closed = true
        unregister()
    }
}

public class UiFrameRendererNotFoundException(public val tag: String) :
    IllegalStateException("No UI frame renderer is registered for tag: $tag")
