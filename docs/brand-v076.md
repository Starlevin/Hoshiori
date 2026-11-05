# 星栞 v0.7.6 品牌素材

图标主题为「星轨书签」：书签轮廓、四芒星和冰蓝/淡紫双色。启动图标保留自适应蒙版安全区，Android13以上增加单色主题图标；四个导航图标使用统一24单位圆头描边的原创ImageVector。

原创二次元形象以星海书签少女为方向，用于品牌介绍；人物不占据签到操作区。可选星海壁纸与人物保持相同色系，用户可以不用背景或导入自己的图片。

## 资源与生成方式

品牌插画使用图像生成工具制作，原始 PNG 与处理记录保存在素材归档中；应用引用的压缩副本已放入仓库，构建所需资源均在仓库内。

- `android/app/src/main/res/drawable-nodpi/hoshiori_mascot_v076.webp`：600×657，透明alpha；原始PNG按比例缩小并编码WebP。
- `android/app/src/main/res/drawable-nodpi/hoshiori_starfield_v076.webp`：941×1672，静态不透明壁纸。
- `android/app/src/main/res/drawable/hoshiori_mark.xml`及`ic_launcher_foreground.xml`：本项目原创矢量标记；未转换第三方图标或角色。
- `ui/HoshioriBrand.kt`：导航ImageVector、品牌标记与形象展示组件。

人物和壁纸由独立提示词生成，没有把用户录屏中的私人网络配置上传到生成服务。录屏只在本机抽帧，用于分析玻璃表现。

## 最终生成提示词

### 人物（transparent_background=true）

Create one original anime mascot illustration for the Android app Hoshiori / 星栞, a calm local game check-in companion themed around stars and bookmarks. Asset: polished transparent-background character cutout, NOT an app mockup or a character sheet. One adult young woman, waist-up three-quarter portrait with both hands fully visible, centered with generous margin. Memorable pale lilac short layered hair fading to icy blue at the tips, a small four-point star plus ribbon-bookmark hair ornament, blue-violet eyes, thoughtful gentle smile. Tasteful midnight-navy high-collar outfit and short lavender-lined cape, subtle silver and mint embroidery, fully clothed. She holds a small open ivory book with a single translucent four-point star hovering over its pages. Refined Japanese anime game illustration, elegant clean linework, precise hands, soft cel shading and restrained luminous highlights. Distinctive readable silhouette even at small sizes, cohesive navy/lilac/icy-blue palette matching a starry glass interface. Original design, no existing franchise character, no text, no lettering, no watermark, no UI frame, no surrounding scenery, no checkerboard. Genuine transparent alpha outside the character.

### 可选壁纸（transparent_background=false）

Create an original optional wallpaper for the Android app Hoshiori / 星栞. Portrait composition, high-end anime environment painting, quiet midnight blue and muted lilac, a serene starlit lake under a wide indigo sky with a very soft pale violet nebula and a few delicate four-point stars. Low distant mountains and a thin silver-blue horizon around the vertical middle; a restrained ribbon of reflected starlight across the water. Fine atmospheric depth, gentle natural gradients and crisp small stars, subtle hand-painted texture. Keep the central reading area and upper half calm and relatively dark so translucent glass UI cards remain legible; attractive colors/contrasting thin lights lower down will reveal lens refraction. No characters, no buildings, no typography, no logos, no interface, no frames, no watermark, no oversaturated neon, no busy particle clouds. The visual identity should feel like a peaceful star bookmark companion, elegant rather than flashy. Complete edge-to-edge opaque illustration.
