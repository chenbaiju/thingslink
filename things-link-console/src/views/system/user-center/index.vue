<template>
  <div class="console-page user-center-page">
    <ConsoleWorkspaceHeader
      project-style
      title="个人中心"
      description="查看当前账号和会话资料；项目身份随当前选择的项目变化。"
      :links="userInfo.currentProjectId ? [] : [{ label: '项目列表', path: '/project/list' }]"
    />
    <ElCard shadow="never" class="user-center-page__profile">
      <div class="profile-identity">
        <img class="profile-identity__avatar" :src="avatarUrl" alt="avatar" />
        <div class="profile-identity__text">
          <h4>{{ displayValue(userInfo.userName) }}</h4>
          <p class="console-description">{{ displayValue(userInfo.email) }}</p>
        </div>
      </div>
    </ElCard>

    <ElAlert
      class="user-center-page__notice"
      type="info"
      show-icon
      :closable="false"
      :title="$t('userCenter.readOnly')"
    />

    <ElCard shadow="never" class="user-center-page__details">
      <template #header>
        <h4>{{ $t('userCenter.accountSection') }}</h4>
      </template>

      <dl class="profile-details">
        <div class="profile-details__item">
          <dt>{{ $t('userCenter.accountId') }}</dt>
          <dd>{{ displayValue(userInfo.userId) }}</dd>
        </div>
        <div class="profile-details__item">
          <dt>{{ $t('userCenter.email') }}</dt>
          <dd>{{ displayValue(userInfo.email) }}</dd>
        </div>
        <div class="profile-details__item">
          <dt>{{ $t('userCenter.tenantId') }}</dt>
          <dd>{{ displayValue(userInfo.tenantId) }}</dd>
        </div>
        <div class="profile-details__item">
          <dt>{{ $t('userCenter.currentProjectId') }}</dt>
          <dd>
            {{ userInfo.currentProjectId ? userInfo.currentProjectId : $t('userCenter.noProject') }}
          </dd>
        </div>
        <div class="profile-details__item profile-details__item--roles">
          <dt>{{ $t('userCenter.roles') }}</dt>
          <dd>
            <template v-if="userInfo.roles?.length">
              <ElTag v-for="role in userInfo.roles" :key="role" effect="plain">{{ role }}</ElTag>
            </template>
            <span v-else>{{ $t('userCenter.noRoles') }}</span>
          </dd>
        </div>
      </dl>
    </ElCard>
    <ProjectInvitations />
  </div>
</template>

<script setup lang="ts">
  import ConsoleWorkspaceHeader from '@/components/business/ConsoleWorkspaceHeader.vue'
  import { storeToRefs } from 'pinia'
  import ProjectInvitations from '@/components/ProjectInvitations.vue'
  import defaultAvatar from '@imgs/user/avatar.webp'
  import { useUserStore } from '@/store/modules/user'

  defineOptions({ name: 'UserCenter' })

  const userStore = useUserStore()
  const { getUserInfo: userInfo } = storeToRefs(userStore)
  const avatarUrl = computed(() => userInfo.value.avatar || defaultAvatar)

  const displayValue = (value?: string) => value?.trim() || '—'
</script>

<style lang="scss" scoped>
  .user-center-page {
    display: flex;
    flex-direction: column;
    height: var(--art-full-height);
    padding: 10px;

    > :not(.project-invitations) {
      flex-shrink: 0;
    }

    :deep(.project-invitations) {
      display: flex;
      flex: 1;
      flex-direction: column;
      min-height: 320px;

      .el-card__header {
        flex-shrink: 0;
      }

      .el-card__body {
        display: flex;
        flex: 1;
        flex-direction: column;
        min-height: 0;
      }

      .console-description,
      .el-alert {
        flex-shrink: 0;
      }

      .el-table__inner-wrapper::before {
        display: none;
      }
    }

    &__header {
      margin-bottom: 10px;

      h3 {
        margin: 0 0 6px;
      }

      p {
        margin: 0;
        color: var(--art-text-gray-500);
      }
    }

    &__profile,
    &__notice {
      margin-bottom: 10px;
    }

    &__details {
      h4 {
        margin: 0;
      }
    }
  }

  .profile-identity {
    display: flex;
    gap: 10px;
    align-items: center;

    &__avatar {
      width: 64px;
      height: 64px;
      object-fit: cover;
      border-radius: 50%;
    }

    &__text {
      min-width: 0;

      h4,
      p {
        margin: 0;
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
      }

      h4 {
        margin-bottom: 6px;
        font-size: 18px;
      }

      p {
        color: var(--art-text-gray-500);
      }
    }
  }

  .profile-details {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 20px 32px;
    margin: 0;

    &__item {
      min-width: 0;

      dt {
        margin-bottom: 8px;
        font-size: 13px;
        color: var(--art-text-gray-500);
      }

      dd {
        margin: 0;
        font-size: 14px;
        overflow-wrap: anywhere;
      }

      .el-tag + .el-tag {
        margin-left: 8px;
      }
    }

    &__item--roles {
      grid-column: 1 / -1;
    }
  }

  @media (width <= 640px) {
    .user-center-page {
      padding: 10px;
    }

    .profile-details {
      grid-template-columns: minmax(0, 1fr);
      gap: 10px;
    }

    .profile-details__item--roles {
      grid-column: auto;
    }
  }
</style>
