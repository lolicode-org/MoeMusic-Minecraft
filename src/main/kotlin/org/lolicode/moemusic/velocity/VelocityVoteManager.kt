package org.lolicode.moemusic.velocity

import org.lolicode.moemusic.api.LocalizedText
import org.lolicode.moemusic.core.config.ModConfigManager
import org.lolicode.moemusic.core.permission.PermissionNodes
import org.lolicode.moemusic.core.playback.SkipVoteService
import org.lolicode.moemusic.core.runtime.ServerRuntimeCoordinator
import java.util.UUID

object VelocityVoteManager {
    data class Result(val success: LocalizedText? = null, val failure: LocalizedText? = null)

    private val votes = SkipVoteService(voteRequiredPercent = { ModConfigManager.config.voteRequiredPercent })

    fun request(userId: UUID): Result {
        val requester = VelocityUsers.active(userId)
            ?: return Result(failure = PermissionNodes.VOTE.deniedMessage)
        val users = VelocityUsers.allActive()
        val controller = ServerRuntimeCoordinator.playbackController
        return when (val result = votes.requestVote(
            requester,
            users,
            controller.currentContext?.track,
            controller.currentTrackSessionId,
        )) {
            is SkipVoteService.RequestResult.Failure -> Result(failure = result.message)
            is SkipVoteService.RequestResult.Passed -> {
                val message = LocalizedText.key(
                    "action.moemusic.playback.vote_passed",
                    result.tally.voteCount,
                    result.tally.requiredVotes,
                    result.tally.title,
                )
                broadcast(message)
                controller.skip()
                Result(success = message)
            }

            is SkipVoteService.RequestResult.Registered -> {
                broadcast(
                    LocalizedText.key(
                        "action.moemusic.playback.vote_broadcast",
                        result.tally.title,
                        result.tally.voteCount,
                        result.tally.requiredVotes,
                    ),
                )
                Result(
                    success = LocalizedText.key(
                        "action.moemusic.playback.vote_registered",
                        result.tally.voteCount,
                        result.tally.requiredVotes,
                    ),
                )
            }

            is SkipVoteService.RequestResult.AlreadyVoted -> Result(
                success = LocalizedText.key(
                    "action.moemusic.playback.vote_already",
                    result.tally.voteCount,
                    result.tally.requiredVotes,
                ),
            )
        }
    }

    fun onLeave(userId: UUID) {
        val controller = ServerRuntimeCoordinator.playbackController
        val tally = votes.onParticipantLeave(
            userId,
            VelocityUsers.allActive(),
            controller.currentContext?.track,
            controller.currentTrackSessionId,
        ) ?: return
        if (tally.passed) {
            broadcast(
                LocalizedText.key(
                    "action.moemusic.playback.vote_passed",
                    tally.voteCount,
                    tally.requiredVotes,
                    tally.title,
                ),
            )
            controller.skip()
        }
    }

    fun reset() = votes.reset()

    private fun broadcast(message: LocalizedText) {
        MoeMusicVelocityPlugin.instance.runOnProxyThread {
            VelocityUsers.allActive().forEach { user ->
                user.player()?.let { VelocityChat.success(it, message) }
            }
        }
    }
}

