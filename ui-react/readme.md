# ui-react

基于react实现的前端页面

## 安装

版本:

- nodejs v18.19.0

```bash
pnpm install
```

本地开发

```bash
pnpm dev
```

打包

```bash
pnpm build
```

打包并发布静态页面到SpringBoot项目的静态目录下

通过自定义脚本来定义发布命令

```json
"scripts": {
  "build:static": "next build",
  "clean:to-spring": "rimraf ../app/src/main/resources/static/*",
  "copy:to-spring": "cpx \"out/**/*\" ../app/src/main/resources/static/",
  "deploy": "npm run build:static && npm run clean:to-spring && npm run copy:to-spring"
}
```

一键发布方式

```bash
pnpm run deploy
```

说明：

- 如果执行上面的发布命令失败，可以尝试将 .next 文件删除之后再重新执行上面的命令
- 发布完成后，需要重启SpringBoot项目，这样才能生效


## 开发部署说明

在本地开发前端页面时，默认调用的也是本地自己的SpringBoot项目的接口，因此我们需要优先启动后台项目；然后再启动前端项目

若后台项目与前台项目不在同一台机器上，需要修改前台项目的[api.ts](./lib/api.ts)文件中的`BASE_URL`为后台项目的IP地址


发布上线时，我们会将前端页面打包成静态文件，然后将这些静态文件部署到SpringBoot项目的静态目录下，这样就可以通过SpringBoot项目来访问前端页面；若果希望将前端静态项目托管到CDN，也需要修改 [api.ts](./lib/api.ts) 文件中的 `BASE_URL`，将后台 `prodcution` 变量对应的参数设置真实的后端接口访问地址