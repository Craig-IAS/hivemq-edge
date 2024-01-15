import { useMutation } from '@tanstack/react-query'
import type { BehaviorPolicy } from '@/api/__generated__'
import { useHttpClient } from '@/api/hooks/useHttpClient/useHttpClient.ts'

export const useCreateBehaviorPolicy = () => {
  const appClient = useHttpClient()

  return useMutation({
    mutationFn: (requestBody: BehaviorPolicy) => {
      return appClient.dataHubBehaviorPolicies.createBehaviorPolicy(requestBody)
    },
  })
}