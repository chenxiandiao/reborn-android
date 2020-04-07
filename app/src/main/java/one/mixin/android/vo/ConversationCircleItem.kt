package one.mixin.android.vo

import android.graphics.Color
import android.os.Parcelable
import androidx.room.ColumnInfo
import kotlin.math.abs
import kotlinx.android.parcel.Parcelize
import one.mixin.android.ui.conversation.holder.BaseViewHolder

@Parcelize
class ConversationCircleItem(
    @ColumnInfo(name = "circle_id")
    val circleId: String,
    @ColumnInfo(name = "name")
    val name: String,
    @ColumnInfo(name = "created_at")
    val createdAt: String?,
    @ColumnInfo(name = "count")
    val count: Int,
    @ColumnInfo(name = "unseen_message_count")
    val unseenMessageCount: Int
) : Parcelable

class CircleOrder(
    val circleId: String,
    val orderAt: String
)

fun ConversationCircleItem?.getCircleColor(): Int {
    return if (this == null) {
        Color.BLACK
    } else {
        val colors = BaseViewHolder.colors
        colors[abs(this.circleId.hashCode()).rem(colors.size)]
    }
}
