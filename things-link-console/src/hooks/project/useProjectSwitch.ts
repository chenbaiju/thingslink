import { useI18n } from 'vue-i18n'
import { fetchSwitchProject } from '@/api/auth'
import type { ProjectResponse } from '@/api/project'
import { useUserStore } from '@/store/modules/user'
import { HttpError } from '@/utils/http/error'

export function useProjectSwitch() {
  const { t } = useI18n()
  const userStore = useUserStore()
  const switchingId = ref('')

  const switchProject = async (project: ProjectResponse) => {
    if (!project.id || project.id === userStore.info.currentProjectId) return
    switchingId.value = project.id
    try {
      const { accessToken } = await fetchSwitchProject(project.id)
      if (!accessToken) {
        throw new Error('切换项目响应中没有 accessToken')
      }

      userStore.setToken(accessToken)
      userStore.setUserInfo({
        ...userStore.info,
        currentProjectId: project.id
      } as Api.Auth.UserInfo)

      ElMessage.success(t('project.enterSuccess', { name: project.name }))

      // 切换项目会改变令牌、权限点和页面数据归属。整页重载最笨，但最不容易留下旧项目状态。
      window.location.assign('/')
    } catch (error) {
      if (!(error instanceof HttpError)) {
        console.error('切换项目失败:', error)
      }
    } finally {
      switchingId.value = ''
    }
  }

  return {
    switchingId,
    switchProject
  }
}
