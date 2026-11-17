# 第三方说明

FAM宝贝相册的项目代码采用 Apache License 2.0。外部工具和依赖由各自项目维护，仍按各自许可使用。本源码包不打包安卓 SDK、JDK、FFmpeg、云厂商服务或测试用对象存储程序。

服务端依赖见 `server/requirements.txt`，包括 FastAPI、Uvicorn、python-multipart、Pillow、Boto3、阿里云 OSS 官方 SDK 和 cryptography。开发测试依赖单独列出，不作为手机应用的一部分。安装依赖时会同时取得相应软件包的许可资料；再分发二进制时应保留相应许可和声明。

安卓客户端使用系统组件。构建时需要 Android SDK 和 JDK。服务器通过外部 FFmpeg 程序读取视频信息、生成预览；不同发行版的 FFmpeg 构建许可可能不同，请以实际安装版本附带的说明为准。

网页后台使用原生网页组件，运行时不依赖外部 CDN。页面交互检查使用 jsdom，版本和开发依赖见 `tools/admin-web-tests/package.json` 与锁文件；这些测试依赖不进入相册服务镜像或安卓安装包。

项目是独立实现的家庭相册，不隶属于其他相册厂商。使用者自行提供的照片、视频和其他资料不在本项目代码许可范围内。
