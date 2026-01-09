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

<script>
export default {
  name: 'BaseButton',
  props: {
    variant: {
      type: String,
      default: 'primary',
      validator: (value) => ['primary', 'secondary', 'white'].includes(value)
    },
    color: {
      type: String,
      default: 'blue',
      validator: (value) => ['blue', 'red', 'green', 'yellow', 'purple'].includes(value)
    },
    size: {
      type: String,
      default: 'md',
      validator: (value) => ['xs', 'sm', 'md', 'lg'].includes(value)
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
      type: String,
      default: 'button',
      validator: (value) => ['button', 'a', 'router-link'].includes(value)
    },
    noWrap: {
      type: Boolean,
      default: false
    }
  },
  computed: {
    buttonClasses() {
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
      const sizeClasses = {
        xs: 'px-2 py-1 text-xs',
        sm: 'px-3 py-1.5 text-sm',
        md: 'px-4 py-2 text-sm',
        lg: 'px-6 py-3 text-base'
      }
      base.push(sizeClasses[this.size])

      // Color class mappings (Tailwind needs full class names)
      const colorClasses = {
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
        const colorConfig = colorClasses[this.color][this.variant]

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
          base.push(colorConfig.bg)
          base.push(colorConfig.hover)
          base.push('border', colorConfig.border)
          base.push('text-gray-700')
          base.push(colorConfig.focus)
          
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
    handleClick(event) {
      if (!this.disabled) {
        this.$emit('click', event)
      }
    }
  }
}
</script>

