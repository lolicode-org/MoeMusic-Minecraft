package org.lolicode.moemusic.platform.command

import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.HoverEvent
import net.minecraft.network.chat.MutableComponent
import org.lolicode.moemusic.api.LocalizedText
import org.lolicode.moemusic.api.MoeMusicUser
import org.lolicode.moemusic.core.config.ModConfigManager
import org.lolicode.moemusic.core.permission.PermissionNodes
import org.lolicode.moemusic.core.playback.SkipVoteService
import org.lolicode.moemusic.platform.player.MinecraftUser
import org.lolicode.moemusic.platform.runtime.MoePlatform
import org.lolicode.moemusic.platform.player.MinecraftUserRegistry
import org.lolicode.moemusic.platform.chat.LocalizedChatRenderer
import java.util.UUID

internal object VoteManager {

    private val voteService: SkipVoteService = SkipVoteService(voteRequiredPercent = {
        ModConfigManager.config.voteRequiredPercent
    })

    data class Result(
        val success: LocalizedText? = null,
        val failure: LocalizedText? = null,
    )

    fun requestVote(userId: UUID): Result {
        val controller = MoePlatform.playbackController
        val requester = MinecraftUserRegistry.getActive(userId)
            ?: return Result(failure = PermissionNodes.VOTE.deniedMessage)
        val registeredUsers = registeredUsers()
        val votableUsers = votableUsers(registeredUsers)

        return when (val result = voteService.requestVote(
            requester = requester,
            activeParticipants = registeredUsers,
            currentTrack = controller.currentContext?.track,
            trackSessionId = controller.currentTrackSessionId,
        )) {
            is SkipVoteService.RequestResult.Failure ->
                Result(failure = result.message)

            is SkipVoteService.RequestResult.Passed -> {
                broadcastPassed(registeredUsers, result.tally)
                controller.skip()
                Result(
                    success = LocalizedText.key(
                        "action.moemusic.playback.vote_passed",
                        result.tally.voteCount,
                        result.tally.requiredVotes,
                        result.tally.title,
                    )
                )
            }

            is SkipVoteService.RequestResult.Registered -> {
                broadcastVote(votableUsers, result.tally)
                Result(
                    success = LocalizedText.key(
                        "action.moemusic.playback.vote_registered",
                        result.tally.voteCount,
                        result.tally.requiredVotes,
                    )
                )
            }

            is SkipVoteService.RequestResult.AlreadyVoted ->
                Result(
                    success = LocalizedText.key(
                        "action.moemusic.playback.vote_already",
                        result.tally.voteCount,
                        result.tally.requiredVotes,
                    )
                )
        }
    }

    fun onUserLeave(userId: UUID) {
        val controller = MoePlatform.playbackController
        val registeredUsers = registeredUsers()
        val tally = voteService.onParticipantLeave(
            userId = userId,
            activeParticipants = registeredUsers,
            currentTrack = controller.currentContext?.track,
            trackSessionId = controller.currentTrackSessionId,
        ) ?: return

        if (tally.passed) {
            broadcastPassed(registeredUsers, tally)
            controller.skip()
        }
    }

    fun reset() {
        voteService.reset()
    }

    private fun canVoteToSkip(user: MoeMusicUser): Boolean =
        user.hasPermission(PermissionNodes.VOTE.id, PermissionNodes.VOTE.defaultLevel())

    private fun registeredUsers(): List<MinecraftUser> =
        MinecraftUserRegistry.allActive().toList()

    private fun votableUsers(users: List<MinecraftUser>): List<MinecraftUser> =
        users.filter(::canVoteToSkip)

    private fun broadcastVote(users: List<MinecraftUser>, update: SkipVoteService.VoteTally) {
        users.forEach { user ->
            user.entity().sendSystemMessage(
                clickableMessage(
                    user = user,
                    message = LocalizedText.key(
                        "action.moemusic.playback.vote_broadcast",
                        update.title,
                        update.voteCount,
                        update.requiredVotes,
                    )
                )
            )
        }
    }

    private fun broadcastPassed(users: List<MinecraftUser>, update: SkipVoteService.VoteTally) {
        users.forEach { user ->
            user.entity().sendSystemMessage(
                plainMessage(
                    user = user,
                    message = LocalizedText.key(
                        "action.moemusic.playback.vote_passed",
                        update.voteCount,
                        update.requiredVotes,
                        update.title,
                    )
                )
            )
        }
    }

    private fun plainMessage(user: MinecraftUser, message: LocalizedText): MutableComponent =
        LocalizedChatRenderer.prefixed(user.locale, message, LocalizedChatRenderer.Tone.SUCCESS)

    private fun clickableMessage(user: MinecraftUser, message: LocalizedText): MutableComponent =
        plainMessage(user, message).withStyle { style ->
            style
                .withClickEvent(ClickEvent.RunCommand("/music skip"))
                .withHoverEvent(
                    HoverEvent.ShowText(
                        LocalizedChatRenderer.component(
                            user.locale,
                            LocalizedText.key("action.moemusic.playback.vote_click"),
                        )
                    )
                )
        }
}
