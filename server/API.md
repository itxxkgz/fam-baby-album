# 服务端接口

服务端与网页后台当前为 2.9.2，配合安卓 App 2.10.0 使用。所有下述接口位于 `/api`；表格和示例省略此前缀。此版本按每个家庭独立部署设计，一套服务可以管理多位宝宝。

`GET /status`、`POST /setup`、`POST /login`、`POST /session` 和 `POST /invitations/accept` 允许直接访问。安卓端其余请求发送 `Authorization: Bearer <token>`，缩略图、原件及视频分段读取也使用同样的请求头。管理员网页使用独立 Cookie 会话，见下方“网页后台登录与权限”；不能将网页会话当成普通家人的网页登录入口。

程序内部的日期使用带时区的标准格式，界面可直接使用 `date_label`、`age` 等中文文字。编号和分页位置只用于程序处理。

## 连接与资料

| 请求 | 内容与结果 |
| --- | --- |
| `GET /status` | `ready`、`setup_required`、`default_credentials`、`app_name`、`message`；`default_credentials` 仅在初始管理员尚未修改默认密码时为真 |
| `POST /setup` | 首次创建管理员：`setup_code`、`username`、`password`、`name`，可选 `child_name`、`birthday` |
| `POST /login` 或 `/session` | 请求 `username`、`password`，不再按称呼选择或创建账户 |
| `GET /me` | 当前成员资料与权限 |
| `POST /me/password` | `current_password`、`new_password`；更换成功后所有旧会话失效，返回新的登录结果 |
| `POST /me/username` | `current_password`、`new_username`；修改登录用户名，保留成员身份和资料归属，所有旧会话失效，返回新的登录结果 |
| `POST /logout` | 注销当前登录 |
| `GET /profile` 或 `/family` | 返回全局家庭名称与当前宝宝资料 |
| `PUT /profile` 或 `/family` | 兼容旧接口：家庭管理员可修改全局 `name`，当前宝宝管理员可修改 `child_name`、`birthday`、`avatar_media_id`；新版宝宝编辑建议使用 `/children/{child_id}` |
| `GET /family/avatar` | 返回导入的宝宝头像 |

首次设置、正常登录、接受邀请、更换密码和更换用户名返回 `token`、`expires_at`、`member` 和首次改密状态。正常登录还返回可访问的 `children`，有可访问宝宝时附 `profile`、`family`；没有可访问宝宝时客户端应显示加入邀请入口，不假定资料一定存在。客户端应保存新令牌，不能保留密码。账号为三到三十二位英文字母、数字、点、横线或下划线，不区分大小写；自己设置的密码为八到二百个字符。

全新数据库首次启动时默认创建 `admin/admin`，仅这个初始密码不受八位长度规则限制。初始登录返回 `password_change_required: true`，成员对象也携带该标志；响应不包含 `profile` 或 `family`，成员权限均为假。客户端必须进入首次修改密码页面，恢复登录时也先读取 `/me` 检查此标志。完成前，仅允许读取自己的身份、修改密码或退出登录，服务器拒绝所有相册资料、文件及管理操作，不能通过旧客户端或直接调用接口绕过。修改完成后换发正常会话，全部初始会话立即失效。

默认账号只在首次新建数据库时创建；已有账号、已导入资料或曾经初始化过的数据库不会被重新设置。显式配置 `ALBUM_BOOTSTRAP_TOKEN` 时保留设置口令初始化方式，已有未完成初始化的相册仍使用原设置流程。

`member` 和 `GET /me` 的对象包含 `id`、`name`、`username`、`role`、`role_label`、`family_role`、`is_family_admin`、`child_id`、`registered`、`disabled`、`joined_at`、`permissions`、`password_change_required`。`role` 是当前宝宝中的有效权限，为 `admin`、`contributor`、`viewer`；`family_role` 是全局账号身份，`is_family_admin` 判断是否家庭管理员。不能只凭 `role=admin` 开放网页后台或存储管理。界面显示对应的 `role_label`。

权限对象包含 `can_upload`、`can_comment`、`can_favorite`、`can_view_trash`、`can_manage_child`、`can_invite_child`，按当前宝宝计算；`can_manage_family`、`can_manage_members`、`can_manage_storage` 仅家庭管理员为真。客户端控制入口，服务器同时强制校验。停用账号使旧令牌立即失效；宝宝权限变化对下一次请求生效。

`GET /status` 中的应用名称为“FAM宝贝相册”。家庭资料中的 `name` 是全家庭共用的相册名称；`child_name`、`child_id`、`birthday`、`avatar_url`、`age`、数量和收录文件大小属于当前宝宝。升级不会覆盖已有名称。

下方资料均为虚构示例。

```json
{
  "name": "FAM宝贝相册",
  "child_name": "宝宝",
  "child_id": "example-child",
  "birthday": "2020-01-01",
  "avatar_url": "/api/family/avatar?child_id=example-child",
  "counts": {"photos": 12, "videos": 3, "members": 2},
  "photos_count": 12,
  "videos_count": 3,
  "members_count": 2,
  "storage_bytes": 104857600,
  "age": "6岁"
}
```

## 多宝宝与请求范围

2.8.0 的 App、服务端和网页应一起升级。未携带宝宝选择信息的旧客户端只能访问服务端默认的可访问宝宝，不能完整使用多宝宝功能。

登录后先读取 `GET /children`。相册、照片列表、成长、故事册、动态、留言和回收站请求发送 `X-FAM-Child: <child_id>`。范围属于每次请求，不写进会话的共享状态。省略时优先选择原默认宝宝；没有权限时选择第一位可访问的未归档宝宝。媒体、缩略图和头像响应中的 URL 已包含 `child_id` 查询参数，浏览器加载这些资源时应使用返回的完整 URL；同时携带请求头和查询参数时必须一致。其他接口不使用查询参数切换宝宝。

客户端切换宝宝时应清除列表、分页位置、播放状态、图片缓存和未提交草稿，并丢弃旧范围的延迟响应；缓存至少区分服务器、账号和宝宝。退出账号清除选择。`/me`、账号修改、宝宝列表、邀请接受、全局家人及存储等接口不会因为旧宝宝已失去权限而被锁死，客户端可以读取可访问宝宝并重新选择；这不代表可以继续读取已被撤回权限的照片。

| 请求 | 内容与结果 |
| --- | --- |
| `GET /children` | 返回 `items`、`selected_child_id`、`default_child_id`、`can_create`、`is_family_admin`；`include_archived=true` 仅让家庭管理员看到已归档宝宝 |
| `POST /children` | 家庭管理员创建宝宝，请求 `name`、可选 `birthday`；返回宝宝对象。先创建和上传照片，再选择头像 |
| `PATCH /children/{child_id}` | 请求可选 `name`、`birthday`、`avatar_media_id`、`archived`；宝宝管理员可改资料，归档或恢复仅家庭管理员可用 |
| `GET /children/{child_id}/members` | 家庭管理员或该宝宝管理员读取已注册家人的 `items` 与 `child`，每位家人返回该宝宝中的有效权限；历史记录通过下方 `/members?include_legacy=true` 读取 |
| `PATCH /children/{child_id}/members/{member_id}` | 请求 `relationship`，仅修改与当前宝宝的关系称呼；返回保存后的成员信息，不改全局账号名和历史上传者 |
| `PUT /children/{child_id}/members/{member_id}` | 请求 `role` 为 `admin`、`contributor`、`viewer` 或 `null`；`null` 取消该宝宝访问权限，不停用家庭账号 |

宝宝成员对象包含 `relationship`、`can_edit_relationship`、`can_change_role`、`can_remove`。没有单独设置关系时，`relationship` 使用原成员名称。关系为去掉首尾空白后的一至四十个字，只允许当前宝宝的管理员修改已经加入的注册家人；家庭管理员和管理员本人也可修改当前宝宝的关系称呼，但权限和移出操作仍受保护。归档宝宝需先恢复。客户端应以这些标记控制可操作状态，服务端同样校验，不能仅隐藏按钮。

家人信息的两个开关使用原角色，不新增独立权限：`viewer` 为管理关、上传关，`contributor` 为管理关、上传开，`admin` 为管理开、上传开。开启管理发送 `admin`，关闭管理发送 `contributor`；管理开启期间不能单独关闭上传。保存期间禁用两开关，失败恢复上一次确认的角色，不能把未保存状态显示为成功。操作必须携带当前宝宝范围。

宝宝对象包含 `id`、`name`、`birthday`、`avatar_media_id`、`avatar_url`、`age`、`archived`、`role`、`permissions` 和 `counts`。名字为 1～40 个字符，生日可以为空，不能晚于今天。宝宝权限包含 `can_upload`、`can_manage`；数量包含 `photos`、`videos`。名字、日期和权限中文名称用于页面，编号仅用于请求。

家庭管理员始终能管理全部宝宝；新增宝宝不会自动加入普通家人。宝宝管理员只能管理自己有权管理的宝宝，不能降低或移除自己的权限，不能直接把该宝宝范围之外的陌生成员加进来，应使用邀请。家庭管理员身份需由家庭成员接口单独设置。

头像必须是当前宝宝已收录且没有移入回收站的照片。归档保留资料、家人访问范围、原件、留言和故事册；普通家人看不到已归档宝宝，家庭管理员可读取并恢复。至少保留一位未归档宝宝。已归档宝宝需先恢复再编辑。

首次升级会一次性建立原宝宝，并把原媒体及回收站状态、家人、邀请、留言、动态、成长代表照和故事册归入其中。旧邀请只授予原宝宝的权限。已有独立账号、密码和会话保留，不重新初始化账号或搬迁原文件。

## 浏览与筛选

以下相册接口全部限制在请求所选宝宝，服务端同时检查家人权限和媒体归属。

`GET /media` 支持 `kind=all|photo|video`、`month=2026-05`、`date=2026-05-17`、`favorite=true`、`deleted=true`、`cursor` 和 `limit`。默认每页 60 项，最多 200 项。返回 `items`、`next_cursor`、`total`。日期按从新到旧排列，同一天按拍摄时间从早到晚排列。下一页原样传回 `next_cursor`。

`GET /media-window` 用于大图连续浏览，必填 `anchor` 为当前照片或视频的编号。它沿用 `/media` 的 `kind`、`month`、`date`、`favorite`、`deleted` 筛选及权限，并按相同顺序返回附近内容。同一拍摄时间再按编号排序，保证翻页顺序稳定。`before` 和 `after` 默认各取12项，分别限制在0到100项之间；服务端只查询附近记录，不将整个相册载入内存。

返回 `items`、`index`、`position`、`total`、`has_before`、`has_after`。`index` 是当前内容在返回窗口中的位置，`position` 是它在完整筛选结果中的位置，两者从0开始。`has_before`、`has_after` 表示返回窗口之外是否仍有内容。继续向后加载时，将窗口最后一项作为 `anchor`，传 `before=0`；向前加载时，将窗口第一项作为 `anchor`，传 `after=0`。每次响应均包含当前项，客户端合并时应按编号去重，并保持当前正在看的照片不变。

当前内容已经移除、不属于指定筛选，或不在该家人的可见范围时，返回404和自然中文提示。未指定日期筛选时可以连续跨日浏览；只有用户明确筛选了某一天，才传 `date`。以上编号和位置仅供程序处理，不代替界面中的照片日期和说明。

`GET /album-locator` 用于相册侧边年龄与日期定位，支持 `kind`、`month`、`date`、`favorite`，遵守当前宝宝的访问权限，不包含回收站。返回 `items`、`total`、`has_birthday`。`items` 按拍摄日从新到旧，每项含 `date`、`age`、`label`、`year`、`count`、`age_months`、`month_label`，只返回实际有内容的拍摄日；`total` 为筛选后的媒体数量。`year` 在有生日时为完整周岁，出生前为 -1，无生日时为日历年份；`age_months` 是完整月数，出生前为 -1，无生日时为空。月底生日按对应月份最后一天计算。界面使用自然中文的 `label` 与 `month_label`，例如“未满1岁”“6岁”“6岁3个月”，无生日时按日历年月显示。

`GET /media` 在 2.8.1 新增 `start_date` 和 `newer_cursor`，与原 `cursor` 三选一。`start_date` 指定当前筛选内实际有内容的日期，包含该日并继续读取更早内容；日期未命中时返回 404，不跳出当前宝宝或筛选范围。`newer_cursor` 从当前页首向较新内容读取紧邻的一页，响应仍按正常相册顺序排列。返回额外的 `has_previous` 与 `previous_cursor`，后者来自本页第一项；`next_cursor` 继续读取更早内容。客户端合并前页时保留当前可见照片及位置，不把用户送回列表顶部。一次响应中的内容、计数和翻页边界使用同一次数据库读取。

`GET /timeline` 支持 `kind`、`month`、`favorite`、`before` 和 `limit`。默认每页 12 个拍摄日期。返回 `groups`、`next_before`、`total`，每组包含 `date`、`age` 和 `items`。同一天的内容完整返回。

媒体对象包含 `id`、`kind`、`width`、`height`、`duration`、`captured_at`、`sort_time`、`album_date`、`description`、`uploader_id`、`uploader_name`、`filename`、`size_bytes`、`favorite`、`comments_count`、`thumbnail_url`、`file_url`、`date_label`、`age`、`created_at`、`updated_at`、`deleted_at`、`shared_count`、`can_edit`、`can_delete`、`can_restore`。`kind` 为 `photo` 或 `video`。图片的 `duration` 可以为空。收藏按当前登录成员与原件独立计算，只通过当前可访问宝宝显示；它不是按宝宝重复建立的收藏。`shared_count` 是原件关联的宝宝数量，`deleted_at` 是当前宝宝的回收站状态。

`sort_time` 是仅供程序排序的数值时间，保留秒以内的精度，不在界面显示。客户端合并或重新排列内容时，应依次按 `album_date` 降序、`sort_time` 升序、`id` 升序处理，与服务端的列表和窗口位置保持一致。不能仅根据 `captured_at` 重建排序时间：导入资料中的拍摄文字可能只精确到秒，同一秒内仍可能有多张先后拍摄的照片。

家庭管理员可以管理全部内容，宝宝管理员管理授权宝宝内容；可上传家人处理自己上传的内容；仅查看家人浏览正常相册并管理收藏。共享原件的时间与说明修改要求在全部关联宝宝中都有这份内容的编辑权限，因此 `can_edit` 可能比 `can_delete` 更严格。移入回收站和恢复只影响当前宝宝。回收站中，当前宝宝管理员能看全部，可上传家人只看自己的内容，仅查看家人不能访问。界面必须使用每项媒体的权限字段显示菜单。

`GET /months` 返回 `months`，其中每项包含 `month`、`count`、`photos` 和 `videos`。

`GET /media/{id}` 返回媒体对象，并附带 `comments` 数组。`GET /media/{id}/file` 支持 GET、HEAD、单段 Range、后缀 Range、ETag 和 If-Range，可用于原件下载和视频拖动播放。`GET /media/{id}/thumbnail` 返回照片预览或视频封面。

## 从小到大

成长选择按宝宝分别保存，年龄使用当前宝宝生日。

`GET /growth` 返回 `birthday`、`can_edit`、`message` 和 `years`。每个年龄段包含 `year`、`label`、`count`、`selected`（媒体对象或空）及 `customized`。仅展示有未移入回收站照片的年龄段，按年龄从小到大排列；视频与出生前照片不进入成长代表照。年龄根据宝宝生日计算完整周年，闰日生日在非闰年以二月最后一天为周年日。第一年显示“出生至1岁”，不会把所有婴儿照片写成“出生当天”。没有生日时返回空列表和说明，不创建默认生日。

`GET /growth/{year}/photos` 支持 `limit`、`cursor`，返回该年龄段的 `items`、`next_cursor`、`total`、`year` 和 `label`。分页规则与相册列表一致：日期由新到旧，同日按保留小数精度的拍摄时间及编号稳定排列。每页默认 60 项，最多 200 项。

`PUT /growth/{year}` 请求 `{ "media_id": "所选照片编号" }`，返回保存后的年龄段资料和 `can_edit`。家庭管理员、当前宝宝管理员和可上传家人可以为这位宝宝选择共同的代表照，仅查看家人只可浏览。所选照片必须仍在相册中且属于该年龄段。已选照片被移除或修改日期后，读取会使用该年龄段另一张有效照片；不会改写原照片或擅自保存新的选择。

## 每月故事册

故事册按宝宝和月份分别保存，包括标题、封面、文字、顺序和版本。

`GET /storybooks` 返回 `months` 和 `can_edit`。每个月包含 `month`、`title`、`cover`（媒体对象或空）、`photos`、`videos`、`total`、`page_count`、`saved` 和 `revision`。目录包含有媒体的月份以及曾保存过的故事册，按月份从新到旧排列。

`GET /storybooks/{month}` 的月份格式为 `2026-05`，返回：

- `month`、`title`、`cover`、`revision`、`can_edit`、`saved`。
- `photos`、`videos`、`total`：当月相册的有效媒体总量，不是已选择的页数。
- `pages`：有顺序的页面，每项含 `media`（完整媒体对象）、`text`、`author_name`。

未整理的月份会读取一份初稿：每天分别挑选一张照片和一段视频，再均匀选取最多 24 页，按时间先后排列，尽量覆盖整月。文字只取已有照片说明，不会生成或推断家庭经历。读取初稿不写入故事册表，初稿的 `saved=false`、`revision=0`。空月返回空页面和空封面。已保存册中被移入回收站或改到其他月份的内容不再返回；封面失效时，读取会回退到册内另一份有效内容。

`PUT /storybooks/{month}` 用于保存整本故事册，请求形状如下，示例编号仅供程序使用：

```json
{
  "title": "春天里的小日子",
  "cover_media_id": "selected-photo",
  "revision": 0,
  "pages": [
    { "media_id": "selected-photo", "text": "全家一起散步。" },
    { "media_id": "selected-video", "text": "" }
  ]
}
```

标题去掉两端空格后必须有内容，最多 120 字；每页文字最多 5000 字；每册保存 1 到 80 页。照片与视频必须属于该月、仍在相册中、没有重复，封面必须在所选页面中。保存会按提交顺序原子替换页面，并返回完整的最新故事册。页文字独立保存，不改写照片的原始说明。原照片说明没有可靠的作者记录，因此初稿署名留空，不把上传者当成写作者。故事册内已有文字未改动时保留原署名；新写或改写的文字署名为本次操作的家人。

家庭管理员、当前宝宝管理员与可上传家人可共同整理这位宝宝的故事册，仅查看家人不能保存。客户端必须提交读取时的 `revision`；保存成功后它递增。两位家人同时编辑时，只有仍持有最新版本的提交能够保存，另一份返回 409 和中文说明。客户端应保留尚未保存的草稿，让用户选择重新载入，不能自动覆盖家人的新修改。编号、分页位置和版本值仅用于程序，不在页面替代正常名称。

## 编辑、收藏与回收站

| 请求 | 内容与结果 |
| --- | --- |
| `PUT` 或 `PATCH /media/{id}` | 可更新 `description`、`captured_at`、`favorite`，返回更新后的媒体详情 |
| `PUT /media/{id}/favorite` | 请求 `favorite` 布尔值 |
| `DELETE /media/{id}` | 移到当前宝宝的回收站，不影响其他宝宝 |
| `GET /trash` | 返回回收站列表，支持 `cursor`、`limit` |
| `POST /media/{id}/restore` | 恢复当前宝宝的收录状态，返回媒体详情 |
| `POST /media/{id}/children` | 请求 `child_ids` 数组，把已有原件加入指定宝宝；保留原关联，原文件仍只存一份 |

时间与原照片说明属于共享原件记录，修改同步影响所有关联宝宝。服务端要求调用者在全部关联宝宝中都能编辑这份内容；客户端提交前应明确提示同步影响。时间编辑影响相册中的排序与显示，原文件字节与原有拍摄信息不变。添加宝宝关联要求在当前全部关联宝宝中都有这份内容的编辑权限，并有权向所有目标宝宝上传；关联中的回收站状态不会自动恢复。

## 上传、留言与家人

`POST /upload` 或 `POST /media` 使用 multipart 表单，文件字段为 `file`，可选 `captured_at`、`fallback_captured_at`、`description`，以及字符串形式的 JSON 数组 `child_ids`，例如 `["child-one","child-two"]`。省略 `child_ids` 时使用当前宝宝；必须有权向每个目标宝宝上传。多选上传只保存一份原件，并为各宝宝建立收录关系。服务器检查完整文件、读取拍摄信息并保存，成功后返回媒体详情。部署时默认单个文件最大 2 GB，可用 `ALBUM_MAX_UPLOAD_BYTES` 调整。家庭管理员可通过 `/admin/settings` 降低全家庭统一的实际限制；实际限制同时用于 App、网页上传和服务器目录导入，并检查实际读入大小。

`captured_at` 仅用于用户明确指定拍摄时间，会覆盖相册记录采用的时间。客户端自动取得的手机相册日期应传入 `fallback_captured_at`，不要作为用户指定时间发送。服务器优先采用原文件的照片原拍摄时间或 Apple 视频原拍摄时间，并按文件中的时区换算；没有这些原拍摄标签时，再采用备用相册时间。若二者均没有，则继续尝试文件中的数字化、修改或视频创建时间，最后才采用本次上传时间。两个时间字段均接受含时区的 ISO 日期时间并检查有效性；不会改写原文件字节。

`GET /media/{id}/comments` 返回当前宝宝的 `items`。同一张合照在不同宝宝相册中的留言与回复分别保存。每条留言包含 `id`、`name`、`text`、`created_at`、`can_delete` 和 `reply_to`。`reply_to` 为被回复的原留言的 `id`、`name`、`text`，没有回复对象或原留言已删除时为空，不再返回已删除留言的文字。`POST /media/{id}/comments` 接受 `text`，仅家庭管理员、当前宝宝管理员和可上传家人能留言；回复和重复发送的处理见下方“家人动态与快捷回应”。`DELETE /media/{id}/comments/{comment_id}` 删除留言，当前宝宝管理员可删除这位宝宝的全部留言，可上传家人只能删除自己的；家庭管理员也可管理。

`GET /members` 返回 `items`，每项为成员对象。家庭管理员读取全家庭成员和每位成员的 `children` 关联；其他家人只读当前宝宝中的基本成员资料及当前宝宝权限。默认仅返回已注册账号，已停用的注册账号仍保留供管理；`profile` 的 `members_count` 与 `counts.members` 采用相同口径，不把历史上传者或待使用邀请算成已加入家人。

管理员可传 `include_legacy=true` 一起读取尚未注册的历史记录，使用每项的 `registered` 区分。宝宝管理员只能读取自己管理的当前宝宝范围，普通家人无权读取历史名单。历史记录保留原上传、点赞、留言和权限关联；客户端将其放在折叠入口，只有需要时才展开或绑定邀请，不删除记录，也不按相同称呼自动合并账号。

`PATCH /members/{id}` 仅家庭管理员可调用。新版传可选 `family_role`、`disabled`：修改家庭身份不改各宝宝授权；停用账号阻止访问全部宝宝。不能关闭或降低自己的家庭管理员身份，不能移除最后一位可登录家庭管理员。兼容字段 `role` 同时改变家庭身份与当前宝宝授权，不与 `family_role` 混用；新版按宝宝改权限应使用 `/children/{child_id}/members/{member_id}`。

邀请接口仅创建时返回明文邀请码，服务器只保存其摘要。2.9.2 新生成的 `code` 是六位 ASCII 数字字符串，必须按字符串传递以保留前导零；以前的长邀请码仍按原有效期验证。新码使用安全随机数，在事务中检查全部历史邀请，已使用、已撤销或已过期的码也不重新分配。碰撞达到重试上限时返回503和自然中文提示，不撤销旧邀请或覆盖记录。邀请短时有效、只能使用一次；管理员绑定旧成员后，接受邀请不会新增重复成员或改变旧称呼。

`POST /invitations/accept` 和 `POST /invitations/join` 共用独立的持久化尝试限制：同一来源15分钟内最多10次，全服务15分钟内最多30次，24小时内最多100次。成功和失败的尝试都计入，重启及多进程不能清空限制；达到上限返回429，并通过 `Retry-After` 给出等待秒数。被限制的请求不延长等待时间。来源由服务接收的连接信息确定，不直接采用客户端自填的转发头；全服务限制同时生效。邀请尝试不再占用账号登录的次数。以上规则不改变账号密码、登录令牌和首次设置口令的长度。

| 请求 | 内容与结果 |
| --- | --- |
| `GET /invitations` | 家庭管理员看全部；宝宝管理员只看自己有权管理的全部目标宝宝的邀请，不能看到家庭管理员邀请；返回 `items` |
| `POST /invitations` | 简单调用只传 `name`，默认邀请加入请求选中的宝宝、`role=viewer`、24小时有效；可选 `child_ids`、`role`、`member_id` 和 `expires_hours`（范围 1–168）；需管理全部目标宝宝；返回邀请对象及 `code` |
| `DELETE /invitations/{id}` | 家庭管理员或有权管理该邀请全部目标宝宝的宝宝管理员，撤销未使用邀请 |
| `POST /invitations/accept` | 新账号无需登录，请求 `code`、`username`、`password`；默认采用邀请中的称呼，仅旧邀请没有称呼时补充 `name`；绑定旧成员时保留称呼与记录，宝宝角色由邀请决定 |
| `POST /invitations/join` | 已有账号登录后请求 `code`，接受宝宝邀请而不新建账号；返回新的登录结果及 `joined_children` 名称列表 |

`name` 为去掉首尾空白后的一至四十个字；显式传入空白称呼会被拒绝，旧客户端完全省略 `name` 的调用继续兼容。创建邀请只保存邀请，不提前创建家人账号；接受邀请后才出现在已加入名单中。

2.9.2 的常规添加界面只填写称呼，固定当前宝宝、仅查看、24小时。旧接口的明确权限、有效期与历史关联参数继续兼容；历史关联放在独立的历史记录入口，不放回添加表单。新账号接受邀请时记录目标宝宝的关系称呼；已有账号加入新宝宝时也可采用邀请中的称呼，已加入的宝宝关系与其他宝宝资料不被覆盖。

新客户端明确传 `child_ids`，避免切换宝宝后邀请范围不清楚。此时 `role=admin` 仅授予所选宝宝管理员权限，不授予家庭管理员。只传称呼时，服务端严格使用 `X-FAM-Child` 指定的宝宝；该宝宝已撤回权限或归档时拒绝创建，不静默改成其他宝宝。只有家庭管理员可沿用明确传 `role=admin` 且省略 `child_ids` 的旧兼容方式创建家庭管理员邀请，新客户端不使用该方式。

邀请对象包含 `id`、`member_id`、`name`、`role`、`role_label`、`child_ids`、`children`、`child_names`、`expires_at`、`status`、`status_label`；状态为 `active`、`used`、`revoked`、`expired`。为同一个未注册成员重新创建邀请，会撤销该成员的旧邀请。

## 存储管理

`/storage` 下的接口均仅家庭管理员可用，对所有宝宝共同生效。`GET /storage` 返回配置列表及服务器允许使用的本地目录；`POST /storage` 新建位置，`PUT /storage/{id}` 更新配置，`POST /storage/{id}/test` 测试完整读写、分段读取和清理，`POST /storage/{id}/default` 设置默认上传位置，`DELETE /storage/{id}` 移除未使用的位置。配置支持 `local`、`oss`、`cos`、`s3`。

密钥只允许写入，不会出现在列表、错误或保存响应中。编辑时省略或留空密钥字段表示保留已有密钥。已有媒体仍从其原来的存储位置读取，切换默认位置仅影响以后上传的内容；正在使用的位置不能删除，已有照片的位置参数不能直接改成另一处地址。媒体接口始终由相册服务鉴权，不向客户端暴露云存储密钥。

## 家人动态与快捷回应

家人动态和回应限制在当前宝宝。切换宝宝必须清空原动态的分页、组编号与未提交草稿；同一张合照在其他宝宝中的留言不混入当前列表。

`GET /activity-feed` 支持 `kind=all|media|comment`、`limit` 和 `cursor`。默认每页 20 组，最多 60 组。返回 `items`、`next_cursor` 和当前成员的 `can_respond`。全部、照片与视频、留言三个筛选均先完成上传合并，再筛选和分页，不会将一批上传拆成多张重复卡片。

上传按发生时间排列，将相邻的同一位家人、同一天、从首项开始 30 分钟以内的上传合为一组。不同家人即使称呼相同也不会合并；缺少可靠成员关联的旧上传不会仅凭称呼合并。日期统一按北京时间计算，跨天不合并。旧导入的真实留言同样能出现在动态中；只有历史事件、原留言已经不存在的记录不展示。读取动态只建立内部浏览位置索引，不改写照片、留言或原始活动记录。

每项动态包含：

| 字段 | 用途 |
| --- | --- |
| `id` | 带本次浏览范围的组编号，仅用于打开这组内容，不解析或显示 |
| `event_key` | 同一历史事件在刷新和切换筛选后保持稳定的内部标识，用于保留草稿及返回位置；上传组重新组成时可能变化 |
| `type` | `upload` 或 `comment` |
| `actor` | 实际成员的 `id`、`name`；没有可靠关联时编号为空，客户端可用称呼绘制头像，不虚构照片 |
| `created_at`、`day` | 发生时间和日期；历史记录的时间无效时为空，客户端显示“时间未记录” |
| `text` | 上传的自然中文说明，如“分享了 6 张照片”；留言则为其实际文字 |
| `media_count`、`photos`、`videos` | 这组当前仍可查看的内容数量 |
| `preview` | 最多 3 项完整媒体对象，上传组按上传先后排列 |
| `response_media` | 回应该动态时关联的完整媒体对象，通常是第一项预览 |
| `comment` | 仅留言动态提供，形状与留言接口相同；其中 `id` 才是回复时使用的原留言编号 |
| `can_respond` | 当前家人是否可以留言或回复 |

下一页原样传回 `next_cursor` 并保持原来的 `kind`。同一次翻页固定在开始浏览时的范围，同一秒内的事件也不会重复或遗漏；新上传和新留言在刷新后出现。切换筛选需从第一页重新读取。已删除留言、回收站中的照片与视频立即从动态中隐藏，包括管理员查看时。

`GET /activity-feed/{id}/media` 打开某组的完整照片与视频，支持 `limit`（默认 60，最多 100）和 `cursor`，返回 `items`、`total`、`next_cursor`。路径中的组编号需进行 URL 编码。此处只返回该组原有的有效内容，新上传不会混进已经打开的组；留言动态只返回它关联的一份内容。客户端应保持接口返回的上传先后顺序，按媒体编号去重，连续浏览限制在这组内容中，不接入全相册窗口。整组内容已被移除时返回 404；若浏览位置因内容永久删除而无法继续，返回 409，可重新打开该组。

快捷留言和回复沿用 `POST /media/{id}/comments`，请求示例如下：

```json
{
  "text": "这个笑容太可爱了！",
  "reply_to_id": "要回复的留言编号，可省略",
  "request_id": "一次发送生成的内部随机标识"
}
```

文字去掉首尾空白后必须有内容，最多 2000 字。`reply_to_id` 只能指向当前宝宝内、同一份照片或视频上仍存在的留言；普通留言不传此字段。`request_id` 可选，为 8 到 100 位英文字母、数字、横线或下划线。客户端对一次发送生成一个随机标识，网络重试沿用它：同一成员提交相同内容、媒体和回复对象时，服务端返回同一条留言，不重复创建。用户主动发起另一条留言时使用新标识。同一标识改了内容、对应留言已经删除，或回复对象已失效时返回 409，不会恢复已删除的文字。旧版客户端只发送 `text` 仍可使用。

仅查看家人可以浏览动态，发送回应返回 403；内容已进入回收站时返回 409。客户端应保留未发出的草稿并说明原因，不能把失败显示为发送成功，也不能自动更换标识后重复发送。

旧版 `GET /activity` 仍支持 `limit`、`before`，返回 `items`、`next_before`。事件包含 `id`、`type`、`text`、`created_at`、`member_name`、`media_id`、`thumbnail_url`、`kind`。`type` 为 `upload` 或 `comment`。新动态页面使用 `/activity-feed` 获取合并及稳定分页后的内容。

## 网页后台登录与权限

网页入口为 `/admin/`，静态页面和接口由同一相册服务提供。后台沿用原家庭管理员账号，不另建一套管理员；宝宝管理员和其他普通成员不能进入。升级不会重置已有账号。全新相册的默认密码仍必须先修改，旧账号的初始化规则不变。

| 请求 | 内容与结果 |
| --- | --- |
| `POST /admin/session` | 请求 `username`、`password`；仅家庭管理员可登录。设置登录 Cookie，返回 `member`、`csrf`、`password_change_required`，不在 JSON 中返回会话令牌 |
| `GET /admin/session` | 使用 Cookie 读取当前管理员和上述页面状态；未登录返回 401，仍需首次改密时允许读取 |
| `DELETE /admin/session` | 使用 Cookie 和防伪请求头退出当前浏览器会话，不影响其他设备 |
| `POST /admin/account` | 请求 `current_password` 及可选 `new_username`、`new_password`，至少提供一个新值；成功后更新 Cookie 并返回新的页面状态 |

Cookie 名为 `fam_admin_session`，设置 `HttpOnly`、`SameSite=Strict`、`Path=/api`，HTTPS 下设置 `Secure`，有效期为三十天。页面恢复登录时调用 `GET /admin/session`，只在内存中保存返回的 `csrf`。Cookie 登录后的非 GET、HEAD、OPTIONS 请求必须发送 `X-FAM-CSRF: <csrf>`，包括沿用的家人、存储和资料修改接口；携带的 `Origin` 必须与当前服务一致。网页登录也检查请求来源。反向代理应传递原 Host 和协议，并只信任部署者指定的代理来源，见部署文档。

尚未完成首次改密时，后台只允许读取会话、退出及提交 `/admin/account` 修改密码；不能只改用户名后进入相册。安卓原有的读取本人资料、修改密码和退出流程仍可用。改用户名或密码会注销该账号的所有旧会话，并给当前浏览器换发新会话；失败时不保存部分修改。

其余 `/admin` 接口均要求已完成首次改密的家庭管理员。有效家庭管理员的 Bearer 请求仍可调用管理接口；网页会话端点自身使用 Cookie，不依赖 Bearer。客户端不得把会话、请求防伪值、内部编号或程序状态字样直接呈现给用户。

## 概览、批量整理与设置

| 请求 | 内容与结果 |
| --- | --- |
| `GET /admin/overview` | 当前宝宝资料、最近上传、本周新增、服务器磁盘空间、默认存储、最近备份及任务摘要 |
| `GET /admin/media` | 管理员筛选与分页，返回 `items`、`total`、`offset`、`has_more` |
| `POST /admin/media/batch` | 对 `ids` 指定的内容执行 `trash`、`restore`、`edit` 或 `link`，返回处理数量和中文说明 |
| `POST /admin/media/download` | 请求 `ids`，返回所选原件的 ZIP，并附中文照片资料；逐份检查原文件完整性，下载完成后清理临时文件 |
| `GET /admin/settings` | 返回实际 `max_upload_bytes`、部署上限 `server_max_upload_bytes` 和后台版本 |
| `PUT /admin/settings` | 请求 `max_upload_bytes`，至少 1 MiB 且不超过部署上限；返回更新后的设置 |
| `GET /admin/records` | 返回最近的整理、账号和设置记录，以及导入和备份结果；供页面显示自然中文摘要 |

概览和照片按 `X-FAM-Child` 选择宝宝；存储、备份、家庭相册名称、上传限制及全局账号管理不随宝宝切换。

`GET /admin/media` 支持 `kind=all|photo|video`、`search`、`uploader_id`、`date_from`、`date_to`、`deleted`、`offset` 和 `limit`。日期范围按拍摄日期筛选，搜索覆盖文件名、说明和上传家人称呼；每页默认 40 项，最多 200 项。顺序为拍摄日期从新到旧、同日按拍摄时间和稳定顺序排列。

批量操作与下载的 `ids` 长度为 1～200，服务端会去重。任何选中项不存在时整体拒绝，批量修改在同一事务中完成。`edit` 接受可选 `captured_at`、`description`，至少传一项；说明最多 5000 字，可设为空，时间按上传接口的规则检查。它修改相册记录，不改原文件；共享时间和说明修改要求全部关联宝宝都可编辑，提交前提示同步影响。`trash`、`restore` 只修改当前宝宝状态。`link` 还接受 `child_ids`，把所选原件加入指定宝宝而不重复存文件。回收站内容需先恢复再编辑或关联。回收站原件仍可由家庭管理员下载。

家庭相册名称使用 `/profile`，宝宝资料与访问范围使用 `/children`；家庭成员、邀请和存储使用 `/members`、`/invitations`、`/storage`。兼容的 `/profile` 仍可编辑当前宝宝资料，字段范围见前文。网页中的 NAS 选项使用已挂载的本地目录，由原有本地存储能力处理。

## 持久化导入任务

导入分为“传到服务器”和“写入相册”两个阶段。浏览器必须先完成文件传输，再启动导入任务；启动后的处理由服务端继续，不依赖浏览器连接。暂存文件和任务都保存在服务端数据目录中，不能在升级时丢弃。

| 请求 | 内容与结果 |
| --- | --- |
| `GET /admin/imports` | 返回最近任务的 `items` |
| `POST /admin/imports` | 请求 `name`、`source: "upload"` 和可选 `child_ids`；默认当前宝宝，创建待接收电脑文件的任务 |
| `GET /admin/imports/{job_id}` | 任务进度和文件结果，最多列出前一千项，更多时 `items_truncated` 为真 |
| `POST /admin/imports/{job_id}/files` | 每次 multipart 上传一个 `file`，可传 `fallback_captured_at`，暂存成功后返回任务摘要 |
| `POST /admin/imports/{job_id}/{operation}` | `operation` 为 `start`、`pause`、`resume`、`retry` 或 `cancel` |
| `GET /admin/imports/roots` | 返回允许的来源目录；页面显示目录名称，内部值仅用于后续请求 |
| `POST /admin/imports/scan` | 请求 `root_id`、可选相对 `path`，返回支持的文件数 `count`、总大小 `bytes`、重复数 `duplicates`、不支持数 `unsupported` |
| `POST /admin/imports/directory` | 请求同扫描，可加 `name`、`child_ids`，创建服务器目录导入任务并排队开始 |

创建时选定的目标宝宝持久保存，上传文件和后续执行都使用这个选择；之后切换页面宝宝不会改变任务目标。工作时仍检查目标宝宝是否有效且可写。

任务摘要包含 `name`、`child_ids`、`child_names`、`source`、`status`、`status_label`、`message`、创建和更新时间，以及 `total`、`processed`、`imported`、`duplicates`、`failed`、`cancelled`、`bytes`。内部状态为 `receiving`、`queued`、`running`、`paused`、`completed`、`failed`、`cancelled`，页面使用对应中文说明。详情项目包含文件名称、状态和处理结果，不返回内部文件校验值。

`start` 只能启动已收到文件的任务；`pause` 暂停排队或运行中的任务；`resume` 继续暂停任务；`retry` 仅将失败项重新排队；`cancel` 停止未处理内容。暂停和取消都允许当前文件先完成，已导入内容保留。服务重启时，上次中断的运行任务转为暂停，等待主动继续。

重复检查按完整文件内容进行，覆盖全家庭已有相册、同任务和回收站。相同原件尚未关联的目标宝宝会新增关联，计入已导入，原文件不再保存一份；全部目标均已有时计为重复。已在目标宝宝回收站中的关联不会自动恢复，会提示去对应回收站处理。拍摄时间沿用普通上传的优先顺序；浏览器可提供文件时间作备用，目录导入使用来源文件修改时间作备用。来源原文件不移动或删除。

目录只允许访问 `ALBUM_LIBRARY` 和 `ALBUM_IMPORT_ROOTS` 指定的绝对目录范围；请求传入相对子路径。拒绝上级路径、隐藏目录和链接绕过，扫描最多检查一万个文件。服务器检查格式、实际大小与剩余空间；损坏文件、变化中的来源或无法访问的存储会作为任务结果说明。文件传输中断后，已收到的文件可先启动导入，尚未收到的文件需要重新上传。

## 备份与独立恢复

| 请求 | 内容与结果 |
| --- | --- |
| `GET /admin/backups` | 最近备份记录的 `items` |
| `POST /admin/backups` | 请求 `mode: "full"` 或 `"metadata"`，默认完整备份；返回 202 和任务，已有备份排队或运行时返回 409 |
| `GET /admin/backups/plan` | 读取备份计划及下一次执行时间 |
| `PUT /admin/backups/plan` | 保存是否启用、每天或每周、执行时间、备份内容和保留数量 |
| `GET /admin/backups/{id}` | 读取备份状态和进度 |
| `GET /admin/backups/{id}/download` | 下载已完成的备份 ZIP |
| `POST /admin/backups/{id}/verify` | 核对备份文件完整性 |
| `POST /admin/backups/{id}/restore-preview` | 核对后返回相册名称、备份时间、照片视频和家人数、备份内容范围 |
| `POST /admin/backups/{id}/restore` | 请求 `current_password`；校验当前管理员密码后恢复独立副本，返回中文说明和副本 `directory` |

计划字段为 `enabled`、`frequency`（`daily` 或 `weekly`）、`time`（24 小时制 `HH:mm`）、`weekday`（周一为 0，周日为 6）、`mode`、`keep`（1～90，默认 7）。时间统一按北京时间计算；关闭浏览器不影响执行，服务中断时正在运行的备份标记为失败，排队任务保留。每次成功备份后按保留数量清理较早已完成备份，手动与定时任务、两种内容范围共用这一数量。

备份范围始终是全家庭，包含所有宝宝、家人权限、共享关系、各宝宝的回收站、留言、成长代表照和故事册，包含已归档宝宝；不受当前 `X-FAM-Child` 选择限制。完整备份还包含有效存储密钥、已有头像和全部已记录原件，包括回收站；共享原件只保存一份。仅资料备份不包含原照片和视频。备份文件只写入 `ALBUM_DATA/backups`，不会自动发送到所选照片存储，可下载后另行留存。ZIP 没有另加密码，需按家庭私有资料保管。

恢复只写入 `ALBUM_DATA/restored` 下的新副本，不替换当前数据库，不自动切换服务。副本清除旧会话和后台任务，账号、宝宝访问权限、共享关系和资料归属保留，当前服务的会话不受影响；完整恢复将原件整理到副本本地，资料恢复仍需接回原存储。生成的“启用副本说明.txt”用于同机器启用；跨机器或 Docker 目录映射变化时，还需部署者调整副本的本地存放路径。当前没有上传外部备份归档并自动迁移路径的接口。

## 错误提示

失败请求返回 `{"detail":"可直接显示给用户的中文说明"}`。登录过期、登录失败、确认密码错误或失效邀请均可能返回 401；客户端不能把所有 401 都当成会话过期，应根据当前操作及会话检查区分。无权访问返回 403，找不到内容返回 404，格式错误返回 422，文件过大返回 413，请求较密返回 429，存储或服务暂时不可用返回 503。
