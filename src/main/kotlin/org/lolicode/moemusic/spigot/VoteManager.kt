package org.lolicode.moemusic.spigot

import org.lolicode.moemusic.api.LocalizedText
import org.lolicode.moemusic.core.config.ModConfigManager
import org.lolicode.moemusic.core.permission.PermissionNodes
import org.lolicode.moemusic.core.playback.SkipVoteService
import org.lolicode.moemusic.core.runtime.ServerRuntimeCoordinator
import java.util.UUID

object VoteManager {
    data class Result(val success: LocalizedText? = null, val failure: LocalizedText? = null)

    private val votes = SkipVoteService(voteRequiredPercent = { ModConfigManager.config.voteRequiredPercent })

    fun request(userId: UUID): Result {
        val requester = SpigotUsers.active(userId)
            ?: return Result(failure = PermissionNodes.VOTE.deniedMessage)
        val users = SpigotUsers.allActive()
        val controller = ServerRuntimeCoordinator.playbackController
        return when (val result = votes.requestVote(
            requester,
            users,
            controller.currentContext?.track,
            controller.currentTrackSessionId,
        )) {
            is SkipVoteService.RequestResult.Failure -> Result(failure = result.message)
            is SkipVoteService.RequestResult.Passed -> {
                broadcast(LocalizedText.key("action.moemusic.playback.vote_passed", result.tally.voteCount, result.tally.requiredVotes, result.tally.title))
                controller.skip()
                Result(success = LocalizedText.key("action.moemusic.playback.vote_passed", result.tally.voteCount, result.tally.requiredVotes, result.tally.title))
            }
            is SkipVoteService.RequestResult.Registered -> {
                broadcast(LocalizedText.key("action.moemusic.playback.vote_broadcast", result.tally.title, result.tally.voteCount, result.tally.requiredVotes))
                Result(success = LocalizedText.key("action.moemusic.playback.vote_registered", result.tally.voteCount, result.tally.requiredVotes))
            }
            is SkipVoteService.RequestResult.AlreadyVoted -> Result(
                success = LocalizedText.key("action.moemusic.playback.vote_already", result.tally.voteCount, result.tally.requiredVotes),
            )
        }
    }

    fun onLeave(userId: UUID) {
        val controller = ServerRuntimeCoordinator.playbackController
        val tally = votes.onParticipantLeave(
            userId,
            SpigotUsers.allActive(),
            controller.currentContext?.track,
            controller.currentTrackSessionId,
        ) ?: return
        if (tally.passed) {
            broadcast(LocalizedText.key("action.moemusic.playback.vote_passed", tally.voteCount, tally.requiredVotes, tally.title))
            controller.skip()
        }
    }

    fun reset() = votes.reset()

    private fun broadcast(message: LocalizedText) {
        MoeMusicPlugin.instance.runOnServerThread {
            SpigotUsers.allActive().forEach { user ->
                user.player()?.let { Chat.success(it, message) }
            }
        }
    }
}
