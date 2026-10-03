package com.zyautra.pushbeam.server

import com.github.f4b6a3.ulid.UlidCreator

/** 접두어 + ULID (docs/02). ULID는 생성 순서대로 정렬된다. */
object Ids {
    fun member() = "mbr_" + ulid()
    fun device() = "dev_" + ulid()
    fun sender() = "snd_" + ulid()
    fun message() = "msg_" + ulid()
    fun delivery() = "dlv_" + ulid()

    private fun ulid() = UlidCreator.getMonotonicUlid().toString()
}
