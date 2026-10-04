<template>
  <div class="page-content !border-0 !bg-transparent min-h-screen flex-cc">
    <div class="console-exception-layout flex-cc max-md:!block max-md:text-center">
      <ThemeSvg :src="data.imgUrl" size="100%" class="!w-100" />
      <div
        class="console-exception-copy ml-15 w-75 max-md:mx-auto max-md:mt-10 max-md:w-full max-md:text-center"
      >
        <p class="text-xl leading-7 text-g-600 max-md:text-lg">{{ data.desc }}</p>
        <ElButton type="primary" size="large" @click="backHome" v-ripple class="mt-5">{{
          data.btnText
        }}</ElButton>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
  import { useCommon } from '@/hooks/core/useCommon'
  import { useUserStore } from '@/store/modules/user'

  const router = useRouter()
  const userStore = useUserStore()

  interface ExceptionData {
    /** 标题 */
    title: string
    /** 描述 */
    desc: string
    /** 按钮文本 */
    btnText: string
    /** 图片地址 */
    imgUrl: string
  }

  withDefaults(
    defineProps<{
      data: ExceptionData
    }>(),
    {}
  )

  const { homePath } = useCommon()

  const backHome = () => {
    const targetHomePath = homePath.value || '/'

    if (!userStore.isLogin) {
      router.push({
        name: 'Login',
        query: { redirect: targetHomePath }
      })
      return
    }

    router.push(targetHomePath)
  }
</script>

<style scoped>
  .console-exception-layout {
    gap: 32px;
    width: min(760px, 100%);
  }
  .console-exception-layout > :first-child {
    width: min(400px, 100%) !important;
  }
  .console-exception-copy {
    min-width: 0;
    margin-left: 0;
  }
  .console-exception-copy .el-button {
    min-width: 112px;
  }
  @media (width <= 768px) {
    .console-exception-layout > :first-child {
      margin-inline: auto;
    }
    .console-exception-copy {
      margin-top: 24px;
    }
  }
</style>
