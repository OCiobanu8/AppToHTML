package com.example.apptohtml.crawler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PathReplayResolverTest {
    @Test
    fun resolve_returns_full_when_all_indices_exist() {
        val root = fakeNode(
            fakeNode(
                fakeNode(),
            ),
        )

        val resolution = resolve(root, listOf(0, 0))

        assertEquals(PathReplayResolver.ResolutionStatus.FULL, resolution.status)
        assertEquals(2, resolution.resolvedDepth)
        assertEquals(3, resolution.nodes.size)
        assertEquals(3, resolution.usableNodes().size)
    }

    @Test
    fun resolve_returns_partial_when_path_diverges_after_some_progress() {
        val root = fakeNode(
            fakeNode(
                fakeNode(),
            ),
        )

        val resolution = resolve(root, listOf(0, 1))

        assertEquals(PathReplayResolver.ResolutionStatus.PARTIAL, resolution.status)
        assertEquals(1, resolution.resolvedDepth)
        assertEquals(1, resolution.failingChildIndex)
        assertEquals(1, resolution.availableChildCount)
        assertEquals(2, resolution.usableNodes().size)
    }

    @Test
    fun resolve_returns_none_when_first_path_segment_is_invalid() {
        val root = fakeNode(
            fakeNode(),
        )

        val resolution = resolve(root, listOf(1))

        assertEquals(PathReplayResolver.ResolutionStatus.NONE, resolution.status)
        assertEquals(0, resolution.resolvedDepth)
        assertEquals(1, resolution.failingChildIndex)
        assertEquals(1, resolution.availableChildCount)
        assertTrue(resolution.usableNodes().isEmpty())
    }

    private fun resolve(
        root: FakeNode,
        path: List<Int>,
    ): PathReplayResolver.Resolution<FakeNode> {
        return PathReplayResolver.resolve(
            root = root,
            childIndexPath = path,
            childCount = { node -> node.children.size },
            childAt = { node, index -> node.children.getOrNull(index) },
        )
    }

    private fun fakeNode(vararg children: FakeNode): FakeNode = FakeNode(children.toList())

    private data class FakeNode(
        val children: List<FakeNode> = emptyList(),
    )
}
