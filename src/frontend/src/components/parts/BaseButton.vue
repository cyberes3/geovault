<template>
  <component
    :is="tag"
    :class="buttonClasses"
    :disabled="disabled"
    :type="tag === 'button' ? type : undefined"
    :title="title"
    v-bind="$attrs"
    @click="handleClick"
  >
    <slot></slot>
    <slot name="icon"></slot>
  </component>
</template>

<script lang="ts">
import { defineComponent, type PropType } from 'vue'

type ButtonVariant = 'primary' | 'secondary' | 'white'
type ButtonColor = 'blue' | 'red' | 'green' | 'yellow' | 'purple' | 'gray'
type ButtonSize = 'xs' | 'sm' | 'md' | 'lg'
type ButtonTag = 'button' | 'a' | 'router-link'

export default defineComponent({
  name: 'BaseButton',
  emits: ['click'],
  props: {
    variant: {
      type: String as PropType<ButtonVariant>,
      default: 'primary',
      validator: (value: string) => ['primary', 'secondary', 'white'].includes(value)
    },
    color: {
      type: String as PropType<ButtonColor>,
      default: 'blue',
      validator: (value: string) => ['blue', 'red', 'green', 'yellow', 'purple', 'gray'].includes(value)
    },
    size: {
      type: String as PropType<ButtonSize>,
      default: 'md',
      validator: (value: string) => ['xs', 'sm', 'md', 'lg'].includes(value)
    },
    disabled: {
      type: Boolean,
      default: false
    },
    type: {
      type: String,
      default: 'button'
    },
    title: {
      type: String,
      default: ''
    },
    tag: {
      type: String as PropType<ButtonTag>,
      default: 'button',
      validator: (value: string) => ['button', 'a', 'router-link'].includes(value)
    },
    noWrap: {
      type: Boolean,
      default: false
    }
  },
  computed: {
    buttonClasses(): string {
      const base = [
        'inline-flex',
        'items-center',
        'justify-center',
        'font-medium',
        'rounded-md',
        'focus:outline-none',
        'focus:ring-2',
        'focus:ring-offset-2',
        'transition-colors',
        'disabled:opacity-50',
        'disabled:cursor-not-allowed'
      ]

      // Size classes
      const sizeClasses: Record<ButtonSize, string> = {
        xs: 'px-2 py-1 text-xs',
        sm: 'px-3 py-1.5 text-sm',
        md: 'px-4 py-2 text-sm',
        lg: 'px-6 py-3 text-base'
      }
      base.push(sizeClasses[this.size])

      // Color class mappings (Tailwind needs full class names)
      const colorClasses: Record<ButtonColor, { primary: { bg: string; hover: string; focus: string }; secondary: { bg: string; hover: string; border: string; focus: string } }> = {
        blue: {
          primary: {
            bg: 'bg-blue-500',
            hover: 'hover:bg-blue-700',
            focus: 'focus:ring-blue-500'
          },
          secondary: {
            bg: 'bg-blue-100',
            hover: 'hover:bg-blue-200',
            border: 'border-blue-500',
            focus: 'focus:ring-blue-500'
          }
        },
        red: {
          primary: {
            bg: 'bg-red-500',
            hover: 'hover:bg-red-700',
            focus: 'focus:ring-red-500'
          },
          secondary: {
            bg: 'bg-red-100',
            hover: 'hover:bg-red-200',
            border: 'border-red-500',
            focus: 'focus:ring-red-500'
          }
        },
        green: {
          primary: {
            bg: 'bg-green-500',
            hover: 'hover:bg-green-700',
            focus: 'focus:ring-green-500'
          },
          secondary: {
            bg: 'bg-green-100',
            hover: 'hover:bg-green-200',
            border: 'border-green-500',
            focus: 'focus:ring-green-500'
          }
        },
        yellow: {
          primary: {
            bg: 'bg-yellow-500',
            hover: 'hover:bg-yellow-700',
            focus: 'focus:ring-yellow-500'
          },
          secondary: {
            bg: 'bg-yellow-100',
            hover: 'hover:bg-yellow-200',
            border: 'border-yellow-500',
            focus: 'focus:ring-yellow-500'
          }
        },
        purple: {
          primary: {
            bg: 'bg-purple-500',
            hover: 'hover:bg-purple-700',
            focus: 'focus:ring-purple-500'
          },
          secondary: {
            bg: 'bg-purple-100',
            hover: 'hover:bg-purple-200',
            border: 'border-purple-500',
            focus: 'focus:ring-purple-500'
          }
        },
        gray: {
          primary: {
            bg: 'bg-gray-600',
            hover: 'hover:bg-gray-700',
            focus: 'focus:ring-gray-500'
          },
          secondary: {
            bg: 'bg-gray-100',
            hover: 'hover:bg-gray-200',
            border: 'border-gray-400',
            focus: 'focus:ring-gray-500'
          }
        }
      }

      if (this.variant === 'white') {
        // White: white background with gray border
        base.push('bg-white')
        base.push('hover:bg-gray-50')
        base.push('border', 'border-gray-300')
        base.push('text-gray-700')
        base.push('focus:ring-blue-500')
        
        // Disabled state for white
        base.push('disabled:bg-gray-100')
        base.push('disabled:hover:bg-gray-100')
        base.push('disabled:border-gray-300')
      } else {
        const colorEntry = colorClasses[this.color]
        const colorConfig = colorEntry[this.variant]

        if (this.variant === 'primary') {
          // Primary: solid background with white text
          base.push(colorConfig.bg)
          base.push(colorConfig.hover)
          base.push('border', 'border-transparent')
          base.push('text-white')
          base.push(colorConfig.focus)
          
          // Disabled state for primary
          base.push('disabled:bg-gray-400')
          base.push('disabled:hover:bg-gray-400')
        } else {
          // Secondary: light background with colored border
          const secondaryConfig = colorEntry.secondary
          base.push(secondaryConfig.bg)
          base.push(secondaryConfig.hover)
          base.push('border', secondaryConfig.border)
          base.push('text-gray-700')
          base.push(secondaryConfig.focus)
          
          // Disabled state for secondary
          base.push('disabled:bg-gray-100')
          base.push('disabled:hover:bg-gray-100')
          base.push('disabled:border-gray-300')
        }
      }

      // Shadow for better visual depth
      base.push('shadow-sm')

      // Whitespace nowrap if needed
      if (this.noWrap) {
        base.push('whitespace-nowrap')
      }

      return base.join(' ')
    }
  },
  methods: {
    handleClick(event: MouseEvent) {
      if (!this.disabled) {
        this.$emit('click', event)
      }
    }
  }
})
</script>

