package dev.lilguy.sim

/**
 * The content with new things going straight to their spots (world.yaml `carry.wait` off), for tests
 * of other mechanics that buy or build a thing and use it at once. WorldTest covers the waiting itself.
 */
internal fun BedroomContent.nothingWaits(): BedroomContent = copy(world = world?.let { it.copy(carry = it.carry.copy(wait = false)) })
